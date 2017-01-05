/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.controller

import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

import spray.http._
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.json._
import spray.json.DefaultJsonProtocol._
import spray.routing.Directives
import whisk.common.TransactionId
import whisk.core.controller.actions.PostActionActivation
import whisk.core.database._
import whisk.core.entity._
import whisk.core.entity.types._
import whisk.http.ErrorResponse.terminate
import whisk.http.Messages
import whisk.core.WhiskConfig

/**
 * A singleton object which defines the properties that must be present in a configuration
 * in order to implement the Meta API.
 */
object WhiskMetaApi {
    def requiredProperties = Map(WhiskConfig.systemKey -> null)
}

trait WhiskMetaApi extends Directives with PostActionActivation {
    services: WhiskServices =>

    /** API path and version for posting activations directly through the host. */
    val apipath: String
    val apiversion: String

    /** Store for identities. */
    protected val authStore: AuthStore

    /** The route prefix e.g., /experimental/package-name. */
    protected val routePrefix = pathPrefix("experimental")

    /** The name and apikey of the system namespace. */
    protected lazy val systemKey = AuthKey(whiskConfig.systemKey)
    protected lazy val systemIdentity = Identity.get(authStore, systemKey)(TransactionId.controller)

    /** Reserved parameters that requests may no defined. */
    protected lazy val reservedProperties = Set("__ow_meta_verb", "__ow_meta_path", "__ow_meta_namespace")

    /** Allowed verbs. */
    private lazy val allowedOperations = get | delete | post

    /** Extracts the HTTP method, query params and unmatched (remaining) path. */
    private val requestMethodParamsAndPath = {
        extract { ctx =>
            val method = ctx.request.method
            val params = ctx.request.message.uri.query.toMap
            val path = ctx.unmatchedPath.toString
            (method, params, path)
        }
    }

    /**
     * Adds route to handle /experimental/package-name to allow a proxy activation of actions
     * contained in package. A meta package must exist in a predefined system namespace.
     * Such packages are self-encoding as "meta" API handlers via an annotation on the package
     * (i.e., annotation "meta" -> true) and an explicit mapping from HTTP verbs to action names
     * to invoke in response to incoming requests. Package bindings are not allowed.
     *
     * The subject making the activation request is subject to entitlement checks for activations
     * (this is tantamount to limiting API requests from a single user) invoking actions directly.
     *
     * A sample meta-package looks like this:
     * WhiskPackage(
     *      EntityPath(systemId),
     *      EntityName("meta-example"),
     *      annotations =
     *          Parameters("meta", JsBoolean(true)) ++
     *          Parameters("get", JsString("action to run on get")) ++
     *          Parameters("post", JsString("action to run on post")) ++
     *          Parameters("delete", JsString("action to run on delete")))
     *
     * In addition, it is a good idea to mark actions in a meta package as final to prevent
     * invoke-time parameters from overriding parameters.
     *
     * A sample final action looks like this:
     * WhiskAction(
     *      EntityPath("systemId/meta-example"),
     *      EntityName("action to run on get"),
     *      annotations =
     *          Parameters("final", JsBoolean(true)))
     *
     * The meta API requests are handled by matching the first segment to a package name in the system
     * namespace. If it exists and it is a valid meta package, the HTTP verb from the request is mapped
     * to a corresponding action and that action is invoked. The action receives as arguments additional
     * context meta data (the verb, the remaining unmatched URI path, and the namespace of the subject
     * making the request). Query parameters and body parameters are passed to the action with the order
     * of precedence being:
     * package.params -> action.params -> query.params -> request.entity (body) -> augment arguments (namespace, path).
     */
    def routes(user: Identity)(implicit transid: TransactionId) = {
        (routePrefix & pathPrefix(EntityName.REGEX.r) & allowedOperations) { s =>
            entity(as[Option[JsObject]]) { body =>
                requestMethodParamsAndPath {
                    case (method, params, restofPath) =>
                        val requestParams = params.toJson.asJsObject.fields ++ { body.map(_.fields) getOrElse Map() }

                        if (reservedProperties.intersect(requestParams.keySet).isEmpty) {
                            onSuccess(resolvePackageName(EntityName(s))) { metaPackage =>
                                process(user, metaPackage, requestParams, restofPath, method)
                            }
                        } else {
                            terminate(BadRequest, Messages.parametersNotAllowed)
                        }
                }
            }
        }
    }

    private def process(
        user: Identity,
        metaPackage: FullyQualifiedEntityName,
        requestParams: Map[String, JsValue],
        restofPath: String,
        method: HttpMethod)(
            implicit transid: TransactionId) = {
        // before checking if package exists, first check that subject has right
        // to post an activation explicitly (i.e., there is no check on the package/action
        // resource since the package is expected to be private)
        def precheck = entitlementProvider.checkThrottles(user) flatMap {
            _ => confirmMetaPackage(pkgLookup(metaPackage), method)
        } flatMap {
            case (actionName, pkgParams) => actionLookup(metaPackage, actionName) map {
                _.inherit(pkgParams)
            }
        }

        def activate(action: WhiskAction): Future[(ActivationId, Option[WhiskActivation])] = {
            // precedence order for parameters:
            // package.params -> action.params -> query.params -> request.entity (body) -> augment arguments (namespace, path)
            systemIdentity flatMap { identity =>
                val invokeParams = if (action.hasFinalParamsAnnotation) {
                    requestParams -- action.parameters.immutableParameters
                } else requestParams // in the absence of immutable annotations, return the request untouched

                if (invokeParams.size == requestParams.size) {
                    val content = invokeParams ++ Map(
                        "__ow_meta_verb" -> method.value.toLowerCase.toJson,
                        "__ow_meta_path" -> restofPath.toJson,
                        "__ow_meta_namespace" -> user.namespace.toJson)
                    invokeAction(identity, action, Some(JsObject(content)), blocking = true, waitOverride = true)
                } else {
                    Future.failed(RejectRequest(BadRequest, Messages.parametersNotAllowed))
                }
            }
        }

        onComplete(precheck flatMap (activate(_))) {
            case Success((activationId, Some(activation))) =>
                val code = if (activation.response.isSuccess) OK else BadRequest
                // if activation error'ed, treat it as a bad request regardless of failure reason
                complete(code, activation.resultAsJson)
            case Success((activationId, None)) =>
                // blocking invoke which got queued instead
                complete(Accepted, JsObject("code" -> transid.id.toJson))

            case Failure(t: RejectRequest) =>
                terminate(t.code, t.message)

            case Failure(t) =>
                error(this, s"exception in meta api handler: $t")
                terminate(InternalServerError)
        }
    }

    /**
     * Resolves the package into using the systemId namespace.
     */
    protected final def resolvePackageName(pkgName: EntityName) = {
        systemIdentity.map { identity =>
            FullyQualifiedEntityName(identity.namespace.toPath, pkgName)
        }
    }

    /**
     * Gets package from datastore. This method is factored out to allow mock testing.
     */
    protected def pkgLookup(pkg: FullyQualifiedEntityName)(
        implicit transid: TransactionId): Future[WhiskPackage] = {
        WhiskPackage.get(entityStore, pkg.toDocId)
    }

    /**
     * Meta API handlers must be in packages (not bindings) and have a "meta -> true" annotation
     * in addition to a mapping from http verbs to action names; fetch package to
     * ensure it exists, if it doesn't reject the request as not allowed.
     * if package exists, check that it satisfies invariants on annotations.
     *
     * @param pkg the whisk package
     * @param method the http verb to look up corresponding action in package annotations
     * @return future that resolves the tuple (action to invoke, package parameters to pass on to action)
     */
    private def confirmMetaPackage(pkgLookup: Future[WhiskPackage], method: HttpMethod)(implicit transid: TransactionId) = {
        pkgLookup recoverWith {
            case _: ArtifactStoreException | DeserializationException(_, _, _) =>
                // if the package lookup fails or the package doesn't conform to expected invariants,
                // fail the request with MethodNotAllowed so as not to leak information about the existence
                // of packages that are otherwise private
                info(this, s"meta api request references package which is missing")
                Future.failed(RejectRequest(MethodNotAllowed))
        } flatMap { pkg =>
            // expecting the meta handlers to be private; should it be an error? warn for now
            if (pkg.publish) {
                warn(this, s"'${pkg.fullyQualifiedName(true)}' is public")
            }

            if (pkg.binding.isEmpty) {
                pkg.annotations.get("meta") filter {
                    // does package have annotation: meta == true
                    _ match { case JsBoolean(b) => b case _ => false }
                } flatMap {
                    // if so, find action name for http verb
                    _ => pkg.annotations.get(method.name.toLowerCase)
                } match {
                    // if action name is defined as a string, accept it, else fail request
                    case Some(JsString(actionName)) =>
                        info(this, s"'${pkg.name}' maps '${method.name}' to action '${actionName}'")
                        Future.successful(EntityName(actionName), pkg.parameters)
                    case _ =>
                        info(this, s"'${pkg.name}' is missing 'meta' annotation or action name for '${method.name.toLowerCase}'")
                        Future.failed(RejectRequest(MethodNotAllowed))
                }
            } else {
                warn(this, s"'${pkg.fullyQualifiedName(true)}' is a binding")
                Future.failed(RejectRequest(MethodNotAllowed))
            }
        }
    }

    protected def actionLookup(pkgName: FullyQualifiedEntityName, actionName: EntityName)(
        implicit transid: TransactionId): Future[WhiskAction] = {
        val docid = pkgName.add(actionName).toDocId
        WhiskAction.get(entityStore, docid) recoverWith {
            case _: ArtifactStoreException | DeserializationException(_, _, _) =>
                // the action doesn't exist or is corrupted but the package stated otherwise
                // so treat this as an internal error
                Future.failed(RejectRequest(InternalServerError))
        }
    }
}

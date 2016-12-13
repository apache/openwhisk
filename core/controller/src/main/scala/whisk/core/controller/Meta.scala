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
    protected val systemId = "whisk.system"
    protected lazy val systemNamespace = EntityPath(systemId)
    protected lazy val systemKey = WhiskAuth.get(authStore, Subject(systemId), false)(TransactionId.controller)

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

    def routes(user: Identity)(implicit transid: TransactionId) = {
        (routePrefix & pathPrefix(EntityName.REGEX.r) & allowedOperations) { s =>
            val metaPackage = resolvePackageName(EntityName(s))

            entity(as[Option[JsObject]]) { body =>
                requestMethodParamsAndPath {
                    case (method, params, restofPath) =>
                        val requestParams = params.toJson.asJsObject.fields ++ { body.map(_.fields) getOrElse Map() }

                        if (reservedProperties.intersect(requestParams.keySet).isEmpty) {
                            process(user, metaPackage, requestParams, restofPath, method)
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

        def activate(action: WhiskAction) = {
            // precedence order for parameters:
            // package.params -> action.params -> query.params -> request.entity (body) -> augment arguments (namespace, path)
            systemKey flatMap {
                val content = requestParams ++ Map(
                    "__ow_meta_verb" -> method.value.toLowerCase.toJson,
                    "__ow_meta_path" -> restofPath.toJson,
                    "__ow_meta_namespace" -> user.namespace.toJson)
                invokeAction(_, action, Some(JsObject(content)), blocking = true, waitOverride = true)
            }
        }

        onComplete(precheck flatMap (activate(_))) {
            case Success((activationId, Some(activation))) =>
                val code = if (activation.response.isSuccess) OK else BadRequest
                // if activation error'ed, treat it as a bad request regardless of failure reason
                complete(code, activation.resultAsJson)
            case Success((activationId, None)) =>
                // blocking invoke which got queued instead
                complete(Accepted, JsObject("code" -> transid().toJson))

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
        FullyQualifiedEntityName(systemNamespace, pkgName)
    }

    /**
     * Gets package from datastore. This method is factored out to allow mock testing.
     */
    protected def pkgLookup(pkg: FullyQualifiedEntityName)(
        implicit transid: TransactionId): Future[WhiskPackage] = {
        WhiskPackage.get(entityStore, pkg.toDocId)
    }

    /**
     * Meta API handlers must be in packages and have a "meta -> true" annotation
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
            if (pkg.publish) warn(this, s"'${pkg.fullyQualifiedName(true)}' is public")

            pkg.annotations("meta") filter {
                // does package have annotatation: meta == true
                _ match { case JsBoolean(b) => b case _ => false }
            } flatMap {
                // if so, find action name for http verb
                _ => pkg.annotations(method.name.toLowerCase)
            } match {
                // if action name is defined as a string, accept it, else fail request
                case Some(JsString(actionName)) =>
                    info(this, s"'${pkg.name}' maps '${method.name}' to action '${actionName}'")
                    Future.successful(EntityName(actionName), pkg.parameters)
                case _ =>
                    info(this, s"'${pkg.name}' is missing 'meta' annotation or action name for '${method.name.toLowerCase}'")
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

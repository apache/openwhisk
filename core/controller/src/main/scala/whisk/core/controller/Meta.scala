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
import scala.util.Try

import spray.http._
import spray.http.HttpHeaders.RawHeader
import spray.http.MediaTypes._
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.json._
import spray.json.DefaultJsonProtocol._
import spray.routing.Directives
import spray.routing.RequestContext
import whisk.common.TransactionId
import whisk.core.controller.actions.BlockingInvokeTimeout
import whisk.core.controller.actions.PostActionActivation
import whisk.core.database._
import whisk.core.entity._
import whisk.core.entity.types._
import whisk.http.ErrorResponse.terminate
import whisk.http.Messages
import whisk.core.WhiskConfig
import whisk.utils.JsHelpers._

private case class Context(
    method: HttpMethod,
    headers: List[HttpHeader],
    path: String,
    query: Map[String, String],
    body: Option[JsObject] = None) {
    val requestParams = query.toJson.asJsObject.fields ++ { body.map(_.fields) getOrElse Map() }
    val overrides = WhiskMetaApi.reservedProperties.intersect(requestParams.keySet)
    def withBody(b: Option[JsObject]) = Context(method, headers, path, query, b)
    def metadata(user: Option[Identity]): Map[String, JsValue] = {
        Map("__ow_meta_verb" -> method.value.toLowerCase.toJson,
            "__ow_meta_headers" -> headers.map(h => h.lowercaseName -> h.value).toMap.toJson,
            "__ow_meta_path" -> path.toJson) ++
            user.map(u => "__ow_meta_namespace" -> u.namespace.asString.toJson)
    }
}

protected[core] object WhiskMetaApi extends Directives {
    /**
     * Defines the properties that must be present in a configuration
     * in order to implement the Meta API.
     */
    def requiredProperties = Map(WhiskConfig.systemKey -> null)

    /** Reserved parameters that requests may no defined. */
    val reservedProperties = Set(
        "__ow_meta_verb",
        "__ow_meta_headers",
        "__ow_meta_path",
        "__ow_meta_namespace")

    /** Supported media types for action response. */
    val mediaTranscoders = Map(
        "html" -> resultAsHtml _,
        "text" -> resultAsText _,
        "json" -> resultAsJson _,
        "http" -> resultAsHttp _)

    /** Default media extensions for action projection. */
    val defaultMediaProjection: Map[String, Option[List[String]]] = Map(
        "html" -> Some(List("html")),
        "text" -> Some(List("text")),
        "json" -> None,
        "http" -> None)

    val supportedMediaTypes = mediaTranscoders.keySet

    protected def resultAsHtml(result: JsValue): RequestContext => Unit = result match {
        case JsString(html) => respondWithMediaType(`text/html`) { complete(OK, html) }
        case _              => complete(BadRequest, Messages.invalidMedia(`text/html`))
    }

    protected def resultAsText(result: JsValue): RequestContext => Unit = {
        result match {
            case r: JsObject  => complete(OK, r.prettyPrint)
            case r: JsArray   => complete(OK, r.prettyPrint)
            case JsString(s)  => complete(OK, s)
            case JsBoolean(b) => complete(OK, b.toString)
            case JsNumber(n)  => complete(OK, n.toString)
            case JsNull       => complete(OK)
        }
    }

    protected def resultAsJson(result: JsValue): RequestContext => Unit = {
        result match {
            case r: JsObject => complete(OK, r)
            case r: JsArray  => complete(OK, r)
            case _           => complete(BadRequest, Messages.invalidMedia(`application/json`))
        }
    }

    protected def resultAsHttp(result: JsValue): RequestContext => Unit = {
        result match {
            case JsObject(fields) => Try {
                val headers = fields.get("headers").map {
                    case JsObject(hs) => hs.map {
                        case (k, JsString(v))  => RawHeader(k, v)
                        case (k, JsBoolean(v)) => RawHeader(k, v.toString)
                        case (k, JsNumber(v))  => RawHeader(k, v.toString)
                        case _                 => throw new Throwable("Invalid header")
                    }.toList

                    case _ => throw new Throwable("Invalid header")
                } getOrElse List()

                val code = fields.get("code").map {
                    case JsNumber(c) =>
                        // the following throws an exception if the code is
                        // not a whole number or a valid code
                        StatusCode.int2StatusCode(c.toIntExact)
                    case _ => throw new Throwable("Illegal code")
                } getOrElse (OK)

                respondWithHeaders(headers) {
                    complete(code, JsObject())
                }
            } getOrElse complete(BadRequest, Messages.invalidMedia(`message/http`))

            case _ => complete(BadRequest, Messages.invalidMedia(`message/http`))
        }
    }
}

trait WhiskMetaApi extends Directives with PostActionActivation {
    services: WhiskServices =>

    /** API path and version for posting activations directly through the host. */
    val apipath: String
    val apiversion: String

    /** Store for identities. */
    protected val authStore: AuthStore

    /** The route prefix e.g., /experimental. */
    protected val routePath = "experimental"

    /** The prefix for anonymous invokes e.g., /experimental/web. */
    protected val anonymousInvokePath = "web"

    /** The name and apikey of the system namespace. */
    protected lazy val systemKey = AuthKey(whiskConfig.systemKey)
    protected lazy val systemIdentity = Identity.get(authStore, systemKey)(TransactionId.controller)

    private val routePrefix = pathPrefix(routePath)
    private val anonymousInvokePrefix = pathPrefix(anonymousInvokePath)

    /** Allowed verbs. */
    private lazy val allowedOperations = get | delete | post

    private lazy val validNameSegment = pathPrefix(EntityName.REGEX.r)
    private lazy val packagePrefix = pathPrefix("default".r | EntityName.REGEX.r)
    private lazy val extensionSplitter = EntityName.REGEX.dropRight(2).concat("""\.([a-zA-Z0-9.+-]+)\z""").r

    /** Extracts the HTTP method, headers, query params and unmatched (remaining) path. */
    private val requestMethodParamsAndPath = {
        extract { ctx =>
            val method = ctx.request.method
            val params = ctx.request.message.uri.query.toMap
            val path = ctx.unmatchedPath.toString
            val headers = ctx.request.headers
            Context(method, headers, path, params)
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
        routePrefix {
            allowedOperations {
                validNameSegment { pkgname =>
                    entity(as[Option[JsObject]]) { body =>
                        requestMethodParamsAndPath { r =>
                            val context = r.withBody(body)
                            if (context.overrides.isEmpty) {
                                val metaPackage = resolvePackageName(EntityName(pkgname))
                                processMetaRequest(user, metaPackage, context)
                            } else {
                                terminate(BadRequest, Messages.parametersNotAllowed)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Adds route to web based activations. Actions invoked this way are anonymous in that the
     * caller is not authenticated. The intended action must be named in the path as a fully qualified
     * name as in /experimental/web/some-namespace/some-package/some-action. The package is optional
     * in that the action may be in the default package, in which case, the string "default" must be used.
     * If the action doesn't exist (or the namespace is not valid) NotFound is generated. Following the
     * action name, an "extension" is required to specify the desired content type for the response. This
     * extension is one of supported media types. An example is ".json" for a JSON response or ".html" for
     * an text/html response.
     *
     * Optionally, the result form the action may be projected based on a named property. As in
     * /experimental/web/some-namespace/some-package/some-action/some-property. If the property
     * does not exist in the result then a NotFound error is generated. A path of properties may
     * be supplied to project nested properties.
     *
     * Actions may be exposed to this web proxy by adding an annotation ("export" -> true).
     */
    def routes()(implicit transid: TransactionId) = {
        (routePrefix & anonymousInvokePrefix) {
            validNameSegment { namespace =>
                packagePrefix { pkg =>
                    pathPrefix(Segment) {
                        _ match {
                            case extensionSplitter(action, extension) =>
                                if (WhiskMetaApi.supportedMediaTypes.contains(extension)) {
                                    val pkgName = if (pkg == "default") None else Some(EntityName(pkg))
                                    handleAnonymousMatch(EntityName(namespace), pkgName, EntityName(action), extension)
                                } else {
                                    complete(NotAcceptable, Messages.contentTypeNotSupported)
                                }
                            case _ => complete(NotAcceptable, Messages.contentTypeRequired)
                        }
                    }
                }
            }
        }
    }

    /**
     * Resolves the package into using the systemId namespace.
     */
    protected final def resolvePackageName(pkgName: EntityName): Future[FullyQualifiedEntityName] = {
        systemIdentity map (_.namespace.addPath(pkgName).toFullyQualifiedEntityName)
    }

    /**
     * Gets package from datastore.
     * This method is factored out to allow mock testing.
     */
    protected def getPackage(pkgName: FullyQualifiedEntityName)(
        implicit transid: TransactionId): Future[WhiskPackage] = {
        WhiskPackage.get(entityStore, pkgName.toDocId)
    }

    /**
     * Gets action from datastore.
     * This method is factored out to allow mock testing.
     */
    protected def getAction(actionName: FullyQualifiedEntityName)(
        implicit transid: TransactionId): Future[WhiskAction] = {
        WhiskAction.get(entityStore, actionName.toDocId)
    }

    /**
     * Gets identity from datastore.
     * This method is factored out to allow mock testing.
     */
    protected def getIdentity(namespace: EntityName)(
        implicit transid: TransactionId): Future[Identity] = {
        Identity.get(authStore, namespace)
    }

    private def processMetaRequest(
        user: Identity,
        pkgpath: Future[FullyQualifiedEntityName],
        context: Context)(
            implicit transid: TransactionId) = {
        // checks that subject has right to post an activation explicitly (note there is
        // no privilege check on the package/action resource since it is expected to be private),
        // and the package plus action and merge their parameters.
        def precheck: Future[(Identity, WhiskAction)] = for {
            // need the system identity
            identity <- systemIdentity

            // the fully resolved path to the system package namespace
            metaPackage <- pkgpath

            // these are sequential, could be done in parallel; in bursts all of the lookups
            // should be cached
            action <- entitlementProvider.checkThrottles(user) flatMap {
                _ => confirmMetaPackage(pkgLookup(metaPackage), context.method)
            } flatMap {
                case (actionName, pkgParams) =>
                    actionLookup(metaPackage.add(actionName), failureCode = InternalServerError) map {
                        _.inherit(pkgParams)
                    }
            }
        } yield (identity, action)

        completeRequest {
            precheck flatMap {
                case (identity, action) => activate(identity, action, context, Some(user))
            }
        }
    }

    private def handleAnonymousMatch(namespace: EntityName, pkg: Option[EntityName], action: EntityName, extension: String)(
        implicit transid: TransactionId) = {
        entity(as[Option[JsObject]]) { body =>
            requestMethodParamsAndPath { r =>
                val context = r.withBody(body)
                val fullname = namespace.addPath(pkg).addPath(action).toFullyQualifiedEntityName
                processAnonymousRequest(fullname, context, extension)
            }
        }
    }

    private def processAnonymousRequest(actionName: FullyQualifiedEntityName, context: Context, responseType: String)(
        implicit transid: TransactionId) = {
        // checks that subject has right to post an activation and fetch the action
        // followed by the package and merge parameters. The action is fetched first since
        // it will not succeed for references relative to a binding, and the export bit is
        // confirmed before fetching the package and merging parameters.
        def precheck: Future[(Identity, WhiskAction)] = for {
            // lookup the identity for the action namespace
            identity <- identityLookup(actionName.path.root) flatMap {
                i => entitlementProvider.checkThrottles(i) map (_ => i)
            }

            // lookup the action - since actions are stored relative to package name
            // the lookup will fail if the package name for the action refers to a binding instead
            action <- confirmExportedAction(actionLookup(actionName, failureCode = NotFound)) flatMap { a =>
                if (a.namespace.defaultPackage) {
                    Future.successful(a)
                } else {
                    pkgLookup(a.namespace.toFullyQualifiedEntityName) map {
                        pkg => (a.inherit(pkg.parameters))
                    }
                }
            }
        } yield (identity, action)

        val projectResultField = {
            Option(context.path)
                .filter(_.nonEmpty)
                .map(_.split("/").filter(_.nonEmpty).toList)
        } orElse WhiskMetaApi.defaultMediaProjection(responseType)

        completeRequest(
            queuedActivation = precheck flatMap {
                case (identity, action) => activate(identity, action, context)
            },
            projectResultField,
            responseType = responseType)
    }

    private def activate(identity: Identity, action: WhiskAction, context: Context, behalfOf: Option[Identity] = None)(
        implicit transid: TransactionId): Future[(ActivationId, Option[WhiskActivation])] = {
        // precedence order for parameters:
        // package.params -> action.params -> query.params -> request.entity (body) -> augment arguments (namespace, path)
        val noOverrides = (context.requestParams.keySet intersect action.immutableParameters).isEmpty
        if (noOverrides) {
            val content = context.requestParams ++ context.metadata(behalfOf)
            invokeAction(identity, action, Some(JsObject(content)), blocking = true, waitOverride = Some(WhiskActionsApi.maxWaitForBlockingActivation))
        } else {
            Future.failed(RejectRequest(BadRequest, Messages.parametersNotAllowed))
        }
    }

    private def completeRequest(
        queuedActivation: Future[(ActivationId, Option[WhiskActivation])],
        projectResultField: Option[List[String]] = None,
        responseType: String = "json")(
            implicit transid: TransactionId) = {
        onComplete(queuedActivation) {
            case Success((activationId, Some(activation))) =>
                val result = activation.resultAsJson

                if (activation.response.isSuccess) {
                    val resultPath = projectResultField getOrElse List()
                    val result = getFieldPath(activation.resultAsJson, resultPath)
                    result match {
                        case Some(projection) =>
                            val marshaler = Future(WhiskMetaApi.mediaTranscoders(responseType)(projection))
                            onComplete(marshaler) {
                                case Success(done) => done // all transcoders terminate the connection
                                case Failure(t)    => terminate(InternalServerError)
                            }
                        case _ => complete(NotFound, Messages.propertyNotFound)
                    }
                } else {
                    complete(BadRequest, result)
                }

            case Success((activationId, None)) =>
                // blocking invoke which got queued instead
                complete(Accepted, JsObject("code" -> transid.id.toJson))

            case Failure(t: BlockingInvokeTimeout) =>
                info(this, s"activation waiting period expired")
                complete(Accepted, JsObject("code" -> transid.id.toJson))

            case Failure(t: RejectRequest) =>
                terminate(t.code, t.message)

            case Failure(t) =>
                error(this, s"exception in meta api handler: $t")
                terminate(InternalServerError)
        }
    }

    /**
     * Gets package from datastore and confirms it is not a binding.
     */
    private def pkgLookup(pkg: FullyQualifiedEntityName)(
        implicit transid: TransactionId): Future[WhiskPackage] = {
        getPackage(pkg).filter {
            _.binding.isEmpty
        } recoverWith {
            case _: ArtifactStoreException | DeserializationException(_, _, _) =>
                // if the package lookup fails or the package doesn't conform to expected invariants,
                // fail the request with BadRequest so as not to leak information about the existence
                // of packages that are otherwise private
                info(this, s"meta api request for package which does not exist")
                Future.failed(RejectRequest(NotFound))
            case _: NoSuchElementException =>
                warn(this, s"'$pkg' is a binding")
                Future.failed(RejectRequest(NotFound))
        }
    }

    /**
     * Gets the action if it exists and fail future with RejectRequest if it does not.
     * Caller should specify desired failure code for failed future.
     * For a meta action, should be InternalServerError since the meta package might be
     * badly configured. And for export errors NotFound is appropriate.
     */
    private def actionLookup(actionName: FullyQualifiedEntityName, failureCode: StatusCode)(
        implicit transid: TransactionId): Future[WhiskAction] = {
        getAction(actionName) recoverWith {
            case _: ArtifactStoreException | DeserializationException(_, _, _) =>
                Future.failed(RejectRequest(failureCode))
        }
    }

    /**
     * Gets the identity for the namespace.
     */
    private def identityLookup(namespace: EntityName)(
        implicit transid: TransactionId): Future[Identity] = {
        getIdentity(namespace) recoverWith {
            case _: ArtifactStoreException | DeserializationException(_, _, _) =>
                Future.failed(RejectRequest(NotFound))
            case t =>
                // leak nothing no matter what, failure is already logged so skip here
                Future.failed(RejectRequest(NotFound))
        }
    }

    /**
     * Meta API handlers must be in packages (not bindings) and have a ("meta" -> true)
     * annotation in addition to a mapping from HTTP verbs to action names; fetch package to
     * ensure it exists and reject request if it does not. If package exists, check that
     * it satisfies invariants on annotations.
     *
     * @param pkgLookup future that resolves to whisk package
     * @param method the HTTP verb to look up corresponding action in package annotations
     * @return future that resolves the tuple (action to invoke, package parameters to pass on to action)
     */
    private def confirmMetaPackage(pkgLookup: Future[WhiskPackage], method: HttpMethod)(
        implicit transid: TransactionId): Future[(EntityName, Parameters)] = {
        pkgLookup flatMap { pkg =>
            // expecting the meta handlers to be private; should it be an error? warn for now
            if (pkg.publish) {
                warn(this, s"'${pkg.fullyQualifiedName(true)}' is public")
            }

            // does package have annotation: meta == true and a
            // mapping from HTTP verb to action name?
            val isMetaPackage = pkg.annotations.asBool("meta").exists(identity)

            if (isMetaPackage) {
                pkg.annotations.asString(method.name.toLowerCase).map { actionName =>
                    // if action name is defined as a string, accept it, else fail request
                    info(this, s"'${pkg.name}' maps '${method.name}' to action '${actionName}'")
                    Future.successful(EntityName(actionName), pkg.parameters)
                } getOrElse {
                    info(this, s"'${pkg.name}' is missing action name for '${method.name.toLowerCase}'")
                    Future.failed(RejectRequest(MethodNotAllowed))
                }
            } else {
                info(this, s"'${pkg.name}' is missing 'meta' annotation")
                Future.failed(RejectRequest(NotFound))
            }
        }
    }

    /**
     * Checks if an action is exported (i.e., carries the required annotation).
     */
    private def confirmExportedAction(actionLookup: Future[WhiskAction])(
        implicit transid: TransactionId): Future[WhiskAction] = {
        actionLookup flatMap { action =>
            if (action.annotations.asBool("web-export").exists(identity)) {
                info(this, s"${action.fullyQualifiedName(true)} is exported")
                Future.successful(action)
            } else {
                info(this, s"${action.fullyQualifiedName(true)} not exported")
                Future.failed(RejectRequest(NotFound))
            }
        }
    }

}

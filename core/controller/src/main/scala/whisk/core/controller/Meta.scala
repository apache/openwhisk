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

import java.util.Base64

import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import spray.http._
import spray.http.HttpHeaders._
import spray.http.MediaTypes._
import spray.http.StatusCodes._
import spray.http.parser.HttpParser
import spray.httpx.SprayJsonSupport._
import spray.json._
import spray.json.DefaultJsonProtocol._
import spray.routing.Directives
import spray.routing.RequestContext
import spray.routing.Route
import whisk.common.TransactionId
import whisk.core.controller.actions.BlockingInvokeTimeout
import whisk.core.controller.actions.PostActionActivation
import whisk.core.database._
import whisk.core.entity._
import whisk.core.entity.types._
import whisk.http.ErrorResponse.terminate
import whisk.http.Messages
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

    /** Reserved parameters that requests may no defined. */
    val reservedProperties = Set(
        "__ow_meta_verb",
        "__ow_meta_headers",
        "__ow_meta_path",
        "__ow_meta_namespace")

    val mediaTranscoders = {
        // extensions are expected to contain only [a-z]
        Seq(MediaExtension("html", Some(List("html")), true, resultAsHtml _),
            MediaExtension("text", Some(List("text")), true, resultAsText _),
            MediaExtension("json", None, true, resultAsJson _),
            MediaExtension("http", None, false, resultAsHttp _))
            .map(e => e.extension -> e).toMap
    }

    val supportedMediaTypes = mediaTranscoders.keySet

    val extensionSplitter = {
        val longestExtension = supportedMediaTypes.map(_.length).max
        // the extension match is not case sensitive so allow a-z and A-Z
        EntityName.REGEX.dropRight(2).concat(raw"\.([a-zA-Z]{$longestExtension})\z").r
    }

    /**
     * Supported extensions, their default projection and transcoder to complete a request.
     *
     * @param extension the supported media types for action response
     * @param defaultProject the default media extensions for action projection
     * @param transcoder the HTTP decoder and terminator for the extension
     */
    protected case class MediaExtension(
        extension: String,
        defaultProjection: Option[List[String]],
        projectionAllowed: Boolean,
        transcoder: (JsValue, TransactionId) => RequestContext => Unit)

    private def resultAsHtml(result: JsValue, transid: TransactionId): RequestContext => Unit = result match {
        case JsString(html) => respondWithMediaType(`text/html`) { complete(OK, html) }
        case _              => terminate(BadRequest, Messages.invalidMedia(`text/html`))(transid)
    }

    private def resultAsText(result: JsValue, transid: TransactionId): RequestContext => Unit = {
        result match {
            case r: JsObject  => complete(OK, r.prettyPrint)
            case r: JsArray   => complete(OK, r.prettyPrint)
            case JsString(s)  => complete(OK, s)
            case JsBoolean(b) => complete(OK, b.toString)
            case JsNumber(n)  => complete(OK, n.toString)
            case JsNull       => complete(OK)
        }
    }

    private def resultAsJson(result: JsValue, transid: TransactionId): RequestContext => Unit = {
        result match {
            case r: JsObject => complete(OK, r)
            case r: JsArray  => complete(OK, r)
            case _           => terminate(BadRequest, Messages.invalidMedia(`application/json`))(transid)
        }
    }

    private def resultAsHttp(result: JsValue, transid: TransactionId): RequestContext => Unit = {
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

                fields.get("body") map {
                    case JsString(str) => interpretHttpResponse(code, headers, str, transid)
                    case _             => terminate(BadRequest, Messages.httpContentTypeError)(transid)
                } getOrElse {
                    respondWithHeaders(headers) {
                        // note that if header defined a content-type, it will be ignored
                        // since the type must be compatible with the data response
                        complete(code)
                    }
                }

            } getOrElse terminate(BadRequest, Messages.invalidMedia(`message/http`))(transid)

            case _ => terminate(BadRequest, Messages.invalidMedia(`message/http`))(transid)
        }
    }

    private def interpretHttpResponse(code: StatusCode, headers: List[RawHeader], str: String, transid: TransactionId): RequestContext => Unit = {
        val parsedHeader: Try[MediaType] = headers.find(_.lowercaseName == `Content-Type`.lowercaseName) match {
            case Some(header) =>
                HttpParser.parseHeader(header) match {
                    case Right(header: `Content-Type`) =>
                        val mediaType = header.contentType.mediaType
                        // lookup the media type specified in the content header to see if it is a recognized type
                        MediaTypes.getForKey(mediaType.mainType -> mediaType.subType).map(Success(_)).getOrElse {
                            // this is a content-type that is not recognized, reject it
                            Failure(RejectRequest(BadRequest, Messages.httpUnknownContentType)(transid))
                        }

                    case _ =>
                        Failure(RejectRequest(BadRequest, Messages.httpUnknownContentType)(transid))
                }
            case None => Success(`text/html`)
        }

        parsedHeader.flatMap { mediaType =>
            if (mediaType.binary) {
                Try(HttpData(Base64.getDecoder().decode(str))).map((mediaType, _))
            } else {
                Success(mediaType, HttpData(str))
            }
        } match {
            case Success((mediaType, data)) =>
                respondWithHeaders(headers) {
                    respondWithMediaType(mediaType) {
                        complete(code, data)
                    }
                }

            case Failure(RejectRequest(code, message)) =>
                terminate(code, message)(transid)

            case _ =>
                terminate(BadRequest, Messages.httpContentTypeError)(transid)
        }
    }
}

trait WhiskMetaApi
    extends Directives
    with ValidateRequestSize
    with PostActionActivation {
    services: WhiskServices =>

    /** API path invocation path for posting activations directly through the host. */
    protected val webInvokePathSegments: Seq[String]

    /** Store for identities. */
    protected val authStore: AuthStore

    /** The prefix for web invokes e.g., /web. */
    private lazy val webRoutePrefix = {
        pathPrefix(webInvokePathSegments.map(segmentStringToPathMatcher(_)).reduceLeft(_ / _))
    }

    /** Allowed verbs. */
    private lazy val allowedOperations = get | delete | post | put

    private lazy val validNameSegment = pathPrefix(EntityName.REGEX.r)
    private lazy val packagePrefix = pathPrefix("default".r | EntityName.REGEX.r)

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

    def routes(user: Identity)(implicit transid: TransactionId): Route = routes(Some(user))
    def routes()(implicit transid: TransactionId): Route = routes(None)

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
    def routes(user: Option[Identity])(implicit transid: TransactionId): Route = {
        (allowedOperations & webRoutePrefix) {
            validNameSegment { namespace =>
                packagePrefix { pkg =>
                    pathPrefix(Segment) {
                        _ match {
                            case WhiskMetaApi.extensionSplitter(action, extension) =>
                                if (WhiskMetaApi.supportedMediaTypes.contains(extension)) {
                                    val pkgName = if (pkg == "default") None else Some(EntityName(pkg))
                                    handleMatch(EntityName(namespace), pkgName, EntityName(action), extension, user)
                                } else {
                                    terminate(NotAcceptable, Messages.contentTypeNotSupported)
                                }
                            case _ => terminate(NotAcceptable, Messages.contentTypeNotSupported)
                        }
                    }
                }
            }
        }
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

    private def handleMatch(namespace: EntityName, pkg: Option[EntityName], action: EntityName, extension: String, onBehalfOf: Option[Identity])(
        implicit transid: TransactionId) = {
        def process(body: Option[JsObject]) = {
            requestMethodParamsAndPath { r =>
                val context = r.withBody(body)
                if (context.overrides.isEmpty) {
                    val fullname = namespace.addPath(pkg).addPath(action).toFullyQualifiedEntityName
                    processRequest(fullname, context, extension, onBehalfOf)
                } else {
                    terminate(BadRequest, Messages.parametersNotAllowed)
                }
            }
        }

        extract(_.request.entity.data.length) { length =>
            validateSize(isWhithinRange(length))(transid) {
                entity(as[Option[JsObject]]) {
                    body => process(body)
                } ~ entity(as[FormData]) {
                    form => process(Some(form.fields.toMap.toJson.asJsObject))
                }
            }
        }
    }

    private def processRequest(actionName: FullyQualifiedEntityName, context: Context, responseType: String, onBehalfOf: Option[Identity])(
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
            action <- confirmExportedAction(actionLookup(actionName, failureCode = NotFound), onBehalfOf) flatMap { a =>
                if (a.namespace.defaultPackage) {
                    Future.successful(a)
                } else {
                    pkgLookup(a.namespace.toFullyQualifiedEntityName) map {
                        pkg => (a.inherit(pkg.parameters))
                    }
                }
            }
        } yield (identity, action)

        val mediaDirectives = WhiskMetaApi.mediaTranscoders(responseType)
        val projectResultField = if (mediaDirectives.projectionAllowed) {
            Option(context.path)
                .filter(_.nonEmpty)
                .map(_.split("/").filter(_.nonEmpty).toList)
                .orElse(mediaDirectives.defaultProjection)
        } else mediaDirectives.defaultProjection

        completeRequest(
            queuedActivation = precheck flatMap {
                case (identity, action) => activate(identity, action, context, onBehalfOf)
            },
            projectResultField,
            responseType = responseType)
    }

    private def activate(identity: Identity, action: WhiskAction, context: Context, onBehalfOf: Option[Identity])(
        implicit transid: TransactionId): Future[(ActivationId, Option[WhiskActivation])] = {
        // precedence order for parameters:
        // package.params -> action.params -> query.params -> request.entity (body) -> augment arguments (namespace, path)
        val noOverrides = (context.requestParams.keySet intersect action.immutableParameters).isEmpty
        if (noOverrides) {
            val content = context.requestParams ++ context.metadata(onBehalfOf)
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

                if (activation.response.isSuccess || activation.response.isApplicationError) {
                    val resultPath = if (activation.response.isSuccess) {
                        projectResultField getOrElse List()
                    } else {
                        // the activation produced an error response: therefore ignore
                        // the requested projection and unwrap the error instead
                        // and attempt to handle it per the desired response type (extension)
                        List(ActivationResponse.ERROR_FIELD)
                    }

                    val result = getFieldPath(activation.resultAsJson, resultPath)
                    result match {
                        case Some(projection) =>
                            val marshaler = Future(WhiskMetaApi.mediaTranscoders(responseType).transcoder(projection, transid))
                            onComplete(marshaler) {
                                case Success(done) => done // all transcoders terminate the connection
                                case Failure(t)    => terminate(InternalServerError)
                            }
                        case _ => terminate(NotFound, Messages.propertyNotFound)
                    }
                } else {
                    terminate(BadRequest, Messages.errorProcessingRequest)
                }

            case Success((activationId, None)) =>
                // blocking invoke which got queued instead
                // this should not happen, instead it should be a blocking invoke timeout
                logging.warn(this, "activation returned an id, expecting timeout error instead")
                terminate(Accepted, Messages.responseNotReady)

            case Failure(t: BlockingInvokeTimeout) =>
                // blocking invoke which timed out waiting on response
                logging.info(this, "activation waiting period expired")
                terminate(Accepted, Messages.responseNotReady)

            case Failure(t: RejectRequest) =>
                terminate(t.code, t.message)

            case Failure(t) =>
                logging.error(this, s"exception in meta api handler: $t")
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
                logging.info(this, s"meta api request for package which does not exist")
                Future.failed(RejectRequest(NotFound))
            case _: NoSuchElementException =>
                logging.warn(this, s"'$pkg' is a binding")
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
     * Checks if an action is exported (i.e., carries the required annotation).
     */
    private def confirmExportedAction(actionLookup: Future[WhiskAction], user: Option[Identity])(
        implicit transid: TransactionId): Future[WhiskAction] = {
        actionLookup flatMap { action =>
            val requiresAuthenticatedUser = action.annotations.asBool("require-whisk-auth").exists(identity)
            val isExported = action.annotations.asBool("web-export").exists(identity)

            if ((isExported && requiresAuthenticatedUser && user.isDefined) ||
                (isExported && !requiresAuthenticatedUser)) {
                logging.info(this, s"${action.fullyQualifiedName(true)} is exported")
                Future.successful(action)
            } else if (!isExported) {
                logging.info(this, s"${action.fullyQualifiedName(true)} not exported")
                Future.failed(RejectRequest(NotFound))
            } else {
                logging.info(this, s"${action.fullyQualifiedName(true)} requires authentication")
                Future.failed(RejectRequest(Unauthorized))
            }
        }
    }
}

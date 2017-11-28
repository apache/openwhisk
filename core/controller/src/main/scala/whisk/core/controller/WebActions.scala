/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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
import scala.util.{Failure, Success, Try}

import akka.http.scaladsl.model.HttpEntity.Empty
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.model.HttpMethod
import akka.http.scaladsl.model.HttpHeader
import akka.http.scaladsl.model.MediaType
import akka.http.scaladsl.model.MediaTypes
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.StatusCode
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.headers.`Content-Type`
import akka.http.scaladsl.model.headers.`Timeout-Access`
import akka.http.scaladsl.model.ContentType
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.model.FormData
import akka.http.scaladsl.model.HttpMethods.{DELETE, GET, HEAD, OPTIONS, PATCH, POST, PUT}
import akka.http.scaladsl.model.HttpCharsets
import akka.http.scaladsl.model.HttpResponse

import spray.json._
import spray.json.DefaultJsonProtocol._

import WhiskWebActionsApi.MediaExtension
import RestApiCommons.{jsonPrettyResponsePrinter => jsonPrettyPrinter}

import whisk.common.TransactionId
import whisk.core.controller.actions.PostActionActivation
import whisk.core.database._
import whisk.core.entity._
import whisk.core.entity.types._
import whisk.http.ErrorResponse.terminate
import whisk.http.Messages
import whisk.utils.JsHelpers._

protected[controller] sealed class WebApiDirectives(prefix: String = "__ow_") {
  // enforce the presence of an extension (e.g., .http) in the URI path
  val enforceExtension = false

  // the field name that represents the status code for an http action response
  val statusCode = "statusCode"

  // parameters that are added to an action input to pass HTTP request context values
  val method: String = fields("method")
  val headers: String = fields("headers")
  val path: String = fields("path")
  val namespace: String = fields("user")
  val query: String = fields("query")
  val body: String = fields("body")

  lazy val reservedProperties: Set[String] = Set(method, headers, path, namespace, query, body)

  protected final def fields(f: String) = s"$prefix$f"
}

private case class Context(propertyMap: WebApiDirectives,
                           method: HttpMethod,
                           headers: Seq[HttpHeader],
                           path: String,
                           query: Query,
                           body: Option[JsValue] = None) {
  val queryAsMap = query.toMap

  // returns true iff the attached query and body parameters contain a property
  // that conflicts with the given reserved parameters
  def overrides(reservedParams: Set[String]): Set[String] = {
    val queryParams = queryAsMap.keySet
    val bodyParams = body
      .map {
        case JsObject(fields) => fields.keySet
        case _                => Set.empty
      }
      .getOrElse(Set.empty)

    (queryParams ++ bodyParams) intersect reservedParams
  }

  // attach the body to the Context
  def withBody(b: Option[JsValue]) = Context(propertyMap, method, headers, path, query, b)

  def metadata(user: Option[Identity]): Map[String, JsValue] = {
    Map(
      propertyMap.method -> method.value.toLowerCase.toJson,
      propertyMap.headers -> headers
        .collect {
          case h if h.name != `Timeout-Access`.name => h.lowercaseName -> h.value
        }
        .toMap
        .toJson,
      propertyMap.path -> path.toJson) ++
      user.map(u => propertyMap.namespace -> u.namespace.asString.toJson)
  }

  def toActionArgument(user: Option[Identity], boxQueryAndBody: Boolean): Map[String, JsValue] = {
    val queryParams = if (boxQueryAndBody) {
      Map(propertyMap.query -> JsString(query.toString))
    } else {
      queryAsMap.map(kv => kv._1 -> JsString(kv._2))
    }

    // if the body is a json object, merge with query parameters
    // otherwise, this is an opaque body that will be nested under
    // __ow_body in the parameters sent to the action as an argument
    val bodyParams = body match {
      case Some(JsObject(fields)) if !boxQueryAndBody => fields
      case Some(v)                                    => Map(propertyMap.body -> v)
      case None if !boxQueryAndBody                   => Map.empty
      case _                                          => Map(propertyMap.body -> JsObject())
    }

    // precedence order is: query params -> body (last wins)
    metadata(user) ++ queryParams ++ bodyParams
  }
}

protected[core] object WhiskWebActionsApi extends Directives {

  private val mediaTranscoders = {
    // extensions are expected to contain only [a-z]
    Seq(
      MediaExtension(".http", None, false, resultAsHttp _),
      MediaExtension(".json", None, true, resultAsJson _),
      MediaExtension(".html", Some(List("html")), true, resultAsHtml _),
      MediaExtension(".svg", Some(List("svg")), true, resultAsSvg _),
      MediaExtension(".text", Some(List("text")), true, resultAsText _))
  }

  private val defaultMediaTranscoder: MediaExtension = mediaTranscoders.find(_.extension == ".http").get

  val allowedExtensions: Set[String] = mediaTranscoders.map(_.extension).toSet

  /**
   * Splits string into a base name plus optional extension.
   * If name ends with ".xxxx" which matches a known extension, accept it as the extension.
   * Otherwise, the extension is ".http" by definition unless enforcing the presence of an extension.
   */
  def mediaTranscoderForName(name: String, enforceExtension: Boolean): (String, Option[MediaExtension]) = {
    mediaTranscoders
      .find(mt => name.endsWith(mt.extension))
      .map { mt =>
        val base = name.dropRight(mt.extensionLength)
        (base, Some(mt))
      }
      .getOrElse {
        (name, if (enforceExtension) None else Some(defaultMediaTranscoder))
      }
  }

  /**
   * Supported extensions, their default projection and transcoder to complete a request.
   *
   * @param extension         the supported media types for action response
   * @param defaultProjection the default media extensions for action projection
   * @param transcoder        the HTTP decoder and terminator for the extension
   */
  protected case class MediaExtension(extension: String,
                                      defaultProjection: Option[List[String]],
                                      projectionAllowed: Boolean,
                                      transcoder: (JsValue, TransactionId, WebApiDirectives) => Route) {
    val extensionLength = extension.length
  }

  private def resultAsHtml(result: JsValue, transid: TransactionId, rp: WebApiDirectives) = result match {
    case JsString(html) => complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, html))
    case _              => terminate(BadRequest, Messages.invalidMedia(`text/html`))(transid, jsonPrettyPrinter)
  }

  private def resultAsSvg(result: JsValue, transid: TransactionId, rp: WebApiDirectives) = result match {
    case JsString(svg) => complete(HttpEntity(`image/svg+xml`, svg.getBytes))
    case _             => terminate(BadRequest, Messages.invalidMedia(`image/svg+xml`))(transid, jsonPrettyPrinter)
  }

  private def resultAsText(result: JsValue, transid: TransactionId, rp: WebApiDirectives) = {
    result match {
      case r: JsObject  => complete(OK, r.prettyPrint)
      case r: JsArray   => complete(OK, r.prettyPrint)
      case JsString(s)  => complete(OK, s)
      case JsBoolean(b) => complete(OK, b.toString)
      case JsNumber(n)  => complete(OK, n.toString)
      case JsNull       => complete(OK, JsNull.toString)
    }
  }

  private def resultAsJson(result: JsValue, transid: TransactionId, rp: WebApiDirectives) = {
    result match {
      case r: JsObject => complete(OK, r)
      case r: JsArray  => complete(OK, r)
      case _           => terminate(BadRequest, Messages.invalidMedia(`application/json`))(transid, jsonPrettyPrinter)
    }
  }

  private def resultAsHttp(result: JsValue, transid: TransactionId, rp: WebApiDirectives) = {
    Try {
      val JsObject(fields) = result
      val headers = fields.get("headers").map {
        case JsObject(hs) =>
          hs.flatMap {
            case (k, v) => headersFromJson(k, v)
          }.toList

        case _ => throw new Throwable("Invalid header")
      } getOrElse List()

      val body = fields.get("body")

      val code = fields.get(rp.statusCode).map {
        case JsNumber(c) =>
          // the following throws an exception if the code is
          // not a whole number or a valid code
          StatusCode.int2StatusCode(c.toIntExact)

        case _ => throw new Throwable("Illegal code")
      }

      body.collect {
        case JsString(str) if str.nonEmpty   => interpretHttpResponse(code.getOrElse(OK), headers, str, transid)
        case JsString(str) /* str.isEmpty */ => respondWithEmptyEntity(code.getOrElse(NoContent), headers)
        case js if js != JsNull              => interpretHttpResponseAsJson(code.getOrElse(OK), headers, js, transid)
      } getOrElse respondWithEmptyEntity(code.getOrElse(NoContent), headers)

    } getOrElse {
      // either the result was not a JsObject or there was an exception validating the
      // response as an http result
      terminate(BadRequest, Messages.invalidMedia(`message/http`))(transid, jsonPrettyPrinter)
    }
  }

  private def respondWithEmptyEntity(code: StatusCode, headers: List[RawHeader]) = {
    respondWithHeaders(removeContentTypeHeader(headers)) {
      // note that if header defined a content-type, it will be ignored
      // since the type must be compatible with the data response
      complete(HttpResponse(code, entity = HttpEntity.Empty))
    }
  }

  private def headersFromJson(k: String, v: JsValue): Seq[RawHeader] = v match {
    case JsString(v)  => Seq(RawHeader(k, v))
    case JsBoolean(v) => Seq(RawHeader(k, v.toString))
    case JsNumber(v)  => Seq(RawHeader(k, v.toString))
    case JsArray(v)   => v.flatMap(inner => headersFromJson(k, inner))
    case _            => throw new Throwable("Invalid header")
  }

  /**
   * Finds the content-type in the header list and ensures that it is a valid format. If it is not
   * valid, construct a failure with appropriate message.
   * If the content-type header is missing, then return the supplied defaultType
   */
  private def findContentTypeInHeader(headers: List[RawHeader],
                                      transid: TransactionId,
                                      defaultType: MediaType): Try[MediaType] = {
    headers.find(_.lowercaseName == `Content-Type`.lowercaseName) match {
      case Some(header) =>
        MediaType.parse(header.value) match {
          case Right(mediaType: MediaType) => Success(mediaType)
          case _                           => Failure(RejectRequest(BadRequest, Messages.httpUnknownContentType)(transid))
        }
      case None => Success(defaultType)
    }
  }

  private def isJsonFamily(mt: MediaType) = {
    mt == `application/json` || mt.value.endsWith("+json")
  }

  private def interpretHttpResponseAsJson(code: StatusCode,
                                          headers: List[RawHeader],
                                          js: JsValue,
                                          transid: TransactionId) = {
    findContentTypeInHeader(headers, transid, `application/json`) match {
      // use the default akka-http response marshaler for standard application/json
      case Success(mediaType) if mediaType == `application/json` =>
        respondWithHeaders(removeContentTypeHeader(headers)) {
          complete(code, js)
        }

      // for all other json-family content-type, explicitly marshal the response;
      // the order of the case statement matters; isJsonFamily returns true for application/json
      case Success(mediaType) if isJsonFamily(mediaType) =>
        respondWithHeaders(removeContentTypeHeader(headers)) {
          complete(
            code,
            HttpEntity(
              ContentType(
                MediaType.customWithFixedCharset(mediaType.mainType, mediaType.subType, HttpCharsets.`UTF-8`)),
              js.prettyPrint))
        }

      case _ =>
        terminate(BadRequest, Messages.httpContentTypeError)(transid, jsonPrettyPrinter)
    }
  }

  private def interpretHttpResponse(code: StatusCode, headers: List[RawHeader], str: String, transid: TransactionId) = {
    findContentTypeInHeader(headers, transid, `text/html`).flatMap { mediaType =>
      val ct = ContentType(mediaType, () => HttpCharsets.`UTF-8`)
      ct match {
        // base64 encoded json will appear as non-binary but it is excluded here for legacy reasons
        case nonbinary: ContentType.NonBinary if !isJsonFamily(mediaType) => Success(HttpEntity(nonbinary, str))

        // because of the default charset provided to the content type constructor
        // the remaining content types to match against are binary at this point, or
        // the legacy base64 encoded json
        case _ /* ContentType.Binary */ => Try(Base64.getDecoder().decode(str)).map(HttpEntity(ct, _))
      }
    } match {
      case Success(entity) =>
        respondWithHeaders(removeContentTypeHeader(headers)) {
          complete(code, entity)
        }

      case Failure(RejectRequest(code, message)) =>
        terminate(code, message)(transid, jsonPrettyPrinter)

      case _ =>
        terminate(BadRequest, Messages.httpContentTypeError)(transid, jsonPrettyPrinter)
    }
  }

  private def removeContentTypeHeader(headers: List[RawHeader]) =
    headers.filter(_.lowercaseName != `Content-Type`.lowercaseName)
}

trait WhiskWebActionsApi extends Directives with ValidateRequestSize with PostActionActivation {
  services: WhiskServices =>

  /** API path invocation path for posting activations directly through the host. */
  protected val webInvokePathSegments: Seq[String]

  /** Mapping of HTTP request fields to action parameter names. */
  protected val webApiDirectives: WebApiDirectives

  /** Store for identities. */
  protected val authStore: AuthStore

  /** The prefix for web invokes e.g., /web. */
  private lazy val webRoutePrefix = {
    pathPrefix(webInvokePathSegments.map(_segmentStringToPathMatcher(_)).reduceLeft(_ / _))
  }

  /** Allowed verbs. */
  private lazy val allowedOperations = get | delete | post | put | head | options | patch

  private lazy val validNameSegment = pathPrefix(EntityName.REGEX.r)
  private lazy val packagePrefix = pathPrefix("default".r | EntityName.REGEX.r)

  private val defaultCorsBaseResponse =
    List(`Access-Control-Allow-Origin`.*, `Access-Control-Allow-Methods`(OPTIONS, GET, DELETE, POST, PUT, HEAD, PATCH))

  private val defaultCorsWithAllowHeader = {
    defaultCorsBaseResponse :+ `Access-Control-Allow-Headers`(`Authorization`.name, `Content-Type`.name)
  }

  private def defaultCorsResponse(headers: Seq[HttpHeader]): List[HttpHeader] = {
    headers.find(_.name == `Access-Control-Request-Headers`.name).map { h =>
      defaultCorsBaseResponse :+ `Access-Control-Allow-Headers`(h.value)
    } getOrElse defaultCorsWithAllowHeader
  }

  private def contentTypeFromEntity(entity: HttpEntity) = entity.contentType match {
    case ct if ct == ContentTypes.NoContentType => None
    case ct                                     => Some(RawHeader(`Content-Type`.lowercaseName, ct.toString))
  }

  /** Extracts the HTTP method, headers, query params and unmatched (remaining) path. */
  private val requestMethodParamsAndPath = {
    extract { ctx =>
      val method = ctx.request.method
      val query = ctx.request.uri.query()
      val path = ctx.unmatchedPath.toString
      val headers = ctx.request.headers ++ contentTypeFromEntity(ctx.request.entity)
      Context(webApiDirectives, method, headers, path, query)
    }
  }

  def routes(user: Identity)(implicit transid: TransactionId): Route = routes(Some(user))

  def routes()(implicit transid: TransactionId): Route = routes(None)

  private val maxWaitForWebActionResult = Some(WhiskActionsApi.maxWaitForBlockingActivation)

  /**
   * Adds route to web based activations. Actions invoked this way are anonymous in that the
   * caller is not authenticated. The intended action must be named in the path as a fully qualified
   * name as in /web/some-namespace/some-package/some-action. The package is optional
   * in that the action may be in the default package, in which case, the string "default" must be used.
   * If the action doesn't exist (or the namespace is not valid) NotFound is generated. Following the
   * action name, an "extension" is required to specify the desired content type for the response. This
   * extension is one of supported media types. An example is ".json" for a JSON response or ".html" for
   * an text/html response.
   *
   * Optionally, the result form the action may be projected based on a named property. As in
   * /web/some-namespace/some-package/some-action/some-property. If the property
   * does not exist in the result then a NotFound error is generated. A path of properties may
   * be supplied to project nested properties.
   *
   * Actions may be exposed to this web proxy by adding an annotation ("export" -> true).
   */
  def routes(user: Option[Identity])(implicit transid: TransactionId): Route = {
    (allowedOperations & webRoutePrefix) {
      validNameSegment { namespace =>
        packagePrefix { pkg =>
          validNameSegment { seg =>
            handleMatch(namespace, pkg, seg, user)
          }
        }
      }
    }
  }

  /**
   * Gets package from datastore.
   * This method is factored out to allow mock testing.
   */
  protected def getPackage(pkgName: FullyQualifiedEntityName)(implicit transid: TransactionId): Future[WhiskPackage] = {
    WhiskPackage.get(entityStore, pkgName.toDocId)
  }

  /**
   * Gets action from datastore.
   * This method is factored out to allow mock testing.
   */
  protected def getAction(actionName: FullyQualifiedEntityName)(
    implicit transid: TransactionId): Future[WhiskActionMetaData] = {
    WhiskActionMetaData.get(entityStore, actionName.toDocId)
  }

  /**
   * Gets identity from datastore.
   * This method is factored out to allow mock testing.
   */
  protected def getIdentity(namespace: EntityName)(implicit transid: TransactionId): Future[Identity] = {
    Identity.get(authStore, namespace)
  }

  private def handleMatch(namespaceSegment: String,
                          pkgSegment: String,
                          actionNameWithExtension: String,
                          onBehalfOf: Option[Identity])(implicit transid: TransactionId) = {

    def fullyQualifiedActionName(actionName: String) = {
      val namespace = EntityName(namespaceSegment)
      val pkgName = if (pkgSegment == "default") None else Some(EntityName(pkgSegment))
      namespace.addPath(pkgName).addPath(EntityName(actionName)).toFullyQualifiedEntityName
    }

    provide(WhiskWebActionsApi.mediaTranscoderForName(actionNameWithExtension, webApiDirectives.enforceExtension)) {
      case (actionName, Some(extension)) =>
        // extract request context, checks for overrides of reserved properties, and constructs action arguments
        // as the context body which may be the incoming request when the content type is JSON or formdata, or
        // the raw body as __ow_body (and query parameters as __ow_query) otherwise
        extract(_.request.entity) { e =>
          validateSize(isWhithinRange(e.contentLengthOption.getOrElse(0)))(transid, jsonPrettyPrinter) {
            requestMethodParamsAndPath { context =>
              provide(fullyQualifiedActionName(actionName)) { fullActionName =>
                onComplete(verifyWebAction(fullActionName, onBehalfOf.isDefined)) {
                  case Success((actionOwnerIdentity, action)) =>
                    if (!action.annotations.asBool("web-custom-options").exists(identity)) {
                      respondWithHeaders(defaultCorsResponse(context.headers)) {
                        if (context.method == OPTIONS) {
                          complete(OK, HttpEntity.Empty)
                        } else {
                          extractEntityAndProcessRequest(actionOwnerIdentity, action, extension, onBehalfOf, context, e)
                        }
                      }
                    } else {
                      extractEntityAndProcessRequest(actionOwnerIdentity, action, extension, onBehalfOf, context, e)
                    }

                  case Failure(t: RejectRequest) =>
                    terminate(t.code, t.message)

                  case Failure(t) =>
                    logging.error(this, s"exception in handleMatch: $t")
                    terminate(InternalServerError)
                }
              }
            }
          }
        }

      case (_, None) =>
        terminate(NotAcceptable, Messages.contentTypeExtensionNotSupported(WhiskWebActionsApi.allowedExtensions))
    }
  }

  /**
   * Checks that subject has right to post an activation and fetch the action
   * followed by the package and merge parameters. The action is fetched first since
   * it will not succeed for references relative to a binding, and the export bit is
   * confirmed before fetching the package and merging parameters.
   *
   * @return Future that completes with the action and action-owner-identity on success otherwise
   *         a failed future with a request rejection error which may be one of the following:
   *         not entitled (throttled), package/action not found, action not web enabled,
   *         or request overrides final parameters
   */
  private def verifyWebAction(actionName: FullyQualifiedEntityName, authenticated: Boolean)(
    implicit transid: TransactionId) = {
    for {
      // lookup the identity for the action namespace
      actionOwnerIdentity <- identityLookup(actionName.path.root) flatMap { i =>
        entitlementProvider.checkThrottles(i) map (_ => i)
      }

      // lookup the action - since actions are stored relative to package name
      // the lookup will fail if the package name for the action refers to a binding instead
      // also merge package and action parameters at the same time
      // precedence order for parameters:
      // package.params -> action.params -> query.params -> request.entity (body) -> augment arguments (namespace, path)
      action <- confirmExportedAction(actionLookup(actionName), authenticated) flatMap { a =>
        if (a.namespace.defaultPackage) {
          Future.successful(a)
        } else {
          pkgLookup(a.namespace.toFullyQualifiedEntityName) map { pkg =>
            (a.inherit(pkg.parameters))
          }
        }
      }
    } yield (actionOwnerIdentity, action)
  }

  private def extractEntityAndProcessRequest(actionOwnerIdentity: Identity,
                                             action: WhiskActionMetaData,
                                             extension: MediaExtension,
                                             onBehalfOf: Option[Identity],
                                             context: Context,
                                             httpEntity: HttpEntity)(implicit transid: TransactionId) = {

    def process(body: Option[JsValue], isRawHttpAction: Boolean) = {
      processRequest(actionOwnerIdentity, action, extension, onBehalfOf, context.withBody(body), isRawHttpAction)
    }

    provide(action.annotations.asBool("raw-http").exists(identity)) { isRawHttpAction =>
      httpEntity match {
        case Empty =>
          process(None, isRawHttpAction)

        case HttpEntity.Strict(ContentTypes.`application/json`, json) if !isRawHttpAction =>
          if (json.nonEmpty) {
            entity(as[JsValue]) { body =>
              process(Some(body), isRawHttpAction)
            }
          } else {
            process(None, isRawHttpAction)
          }

        case HttpEntity.Strict(ContentType(MediaTypes.`application/x-www-form-urlencoded`, _), _) if !isRawHttpAction =>
          entity(as[FormData]) { form =>
            val body = form.fields.toMap.toJson.asJsObject
            process(Some(body), isRawHttpAction)
          }

        case HttpEntity.Strict(contentType, data) =>
          // application/json is not a binary type in Akka, but is binary in Spray
          if (contentType.mediaType.binary || contentType.mediaType == `application/json`) {
            Try(JsString(Base64.getEncoder.encodeToString(data.toArray))) match {
              case Success(bytes) => process(Some(bytes), isRawHttpAction)
              case Failure(t)     => terminate(BadRequest, Messages.unsupportedContentType(contentType.mediaType))
            }
          } else {
            val str = JsString(data.utf8String)
            process(Some(str), isRawHttpAction)
          }

        case _ => terminate(BadRequest, Messages.unsupportedContentType)
      }
    }
  }

  private def processRequest(actionOwnerIdentity: Identity,
                             action: WhiskActionMetaData,
                             responseType: MediaExtension,
                             onBehalfOf: Option[Identity],
                             context: Context,
                             isRawHttpAction: Boolean)(implicit transid: TransactionId) = {

    def queuedActivation = {
      // checks (1) if any of the query or body parameters override final action parameters
      // computes overrides if any relative to the reserved __ow_* properties, and (2) if
      // action is a raw http handler
      //
      // NOTE: it is assumed the action parameters do not intersect with the reserved properties
      // since these are system properties, the action should not define them, and if it does,
      // they will be overwritten
      if (isRawHttpAction || context
            .overrides(webApiDirectives.reservedProperties ++ action.immutableParameters)
            .isEmpty) {
        val content = context.toActionArgument(onBehalfOf, isRawHttpAction)
        invokeAction(actionOwnerIdentity, action, Some(JsObject(content)), maxWaitForWebActionResult, cause = None)
      } else {
        Future.failed(RejectRequest(BadRequest, Messages.parametersNotAllowed))
      }
    }

    completeRequest(queuedActivation, projectResultField(context, responseType), responseType)
  }

  private def completeRequest(queuedActivation: Future[Either[ActivationId, WhiskActivation]],
                              projectResultField: => List[String],
                              responseType: MediaExtension)(implicit transid: TransactionId) = {
    onComplete(queuedActivation) {
      case Success(Right(activation)) =>
        val result = activation.resultAsJson

        if (activation.response.isSuccess || activation.response.isApplicationError) {
          val resultPath = if (activation.response.isSuccess) {
            projectResultField
          } else {
            // the activation produced an error response: therefore ignore
            // the requested projection and unwrap the error instead
            // and attempt to handle it per the desired response type (extension)
            List(ActivationResponse.ERROR_FIELD)
          }

          val result = getFieldPath(activation.resultAsJson, resultPath)
          result match {
            case Some(projection) =>
              val marshaler = Future(responseType.transcoder(projection, transid, webApiDirectives))
              onComplete(marshaler) {
                case Success(done) => done // all transcoders terminate the connection
                case Failure(t)    => terminate(InternalServerError)
              }
            case _ => terminate(NotFound, Messages.propertyNotFound)
          }
        } else {
          terminate(BadRequest, Messages.errorProcessingRequest)
        }

      case Success(Left(activationId)) =>
        // blocking invoke which got queued instead
        // this should not happen, instead it should be a blocking invoke timeout
        logging.info(this, "activation waiting period expired")
        terminate(Accepted, Messages.responseNotReady)

      case Failure(t: RejectRequest) => terminate(t.code, t.message)

      case Failure(t) =>
        logging.error(this, s"exception in completeRequest: $t")
        terminate(InternalServerError)
    }
  }

  /**
   * Gets package from datastore and confirms it is not a binding.
   */
  private def pkgLookup(pkg: FullyQualifiedEntityName)(implicit transid: TransactionId): Future[WhiskPackage] = {
    getPackage(pkg).filter {
      _.binding.isEmpty
    } recoverWith {
      case _: ArtifactStoreException | DeserializationException(_, _, _) =>
        // if the package lookup fails or the package doesn't conform to expected invariants,
        // fail the request with BadRequest so as not to leak information about the existence
        // of packages that are otherwise private
        logging.info(this, s"package which does not exist")
        Future.failed(RejectRequest(NotFound))
      case _: NoSuchElementException =>
        logging.warn(this, s"'$pkg' is a binding")
        Future.failed(RejectRequest(NotFound))
    }
  }

  /**
   * Gets the action if it exists and fail future with RejectRequest if it does not.
   *
   * @return future action document or NotFound rejection
   */
  private def actionLookup(actionName: FullyQualifiedEntityName)(
    implicit transid: TransactionId): Future[WhiskActionMetaData] = {
    getAction(actionName) recoverWith {
      case _: ArtifactStoreException | DeserializationException(_, _, _) =>
        Future.failed(RejectRequest(NotFound))
    }
  }

  /**
   * Gets the identity for the namespace.
   */
  private def identityLookup(namespace: EntityName)(implicit transid: TransactionId): Future[Identity] = {
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
  private def confirmExportedAction(actionLookup: Future[WhiskActionMetaData], authenticated: Boolean)(
    implicit transid: TransactionId): Future[WhiskActionMetaData] = {
    actionLookup flatMap { action =>
      val requiresAuthenticatedUser = action.annotations.asBool("require-whisk-auth").exists(identity)
      val isExported = action.annotations.asBool("web-export").exists(identity)

      if ((isExported && requiresAuthenticatedUser && authenticated) ||
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

  /**
   * Determines the result projection path, if any.
   *
   * @return optional list of projections
   */
  private def projectResultField(context: Context, responseType: MediaExtension): List[String] = {
    val projection = if (responseType.projectionAllowed) {
      Option(context.path)
        .filter(_.nonEmpty)
        .map(_.split("/").filter(_.nonEmpty).toList)
        .orElse(responseType.defaultProjection)
    } else responseType.defaultProjection

    projection.getOrElse(List())
  }
}

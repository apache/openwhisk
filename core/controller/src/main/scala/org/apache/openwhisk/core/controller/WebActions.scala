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

package org.apache.openwhisk.core.controller

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
import akka.http.scaladsl.model.headers.`Content-Type`
import akka.http.scaladsl.model.headers.`Timeout-Access`
import akka.http.scaladsl.model.ContentType
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.model.FormData
import akka.http.scaladsl.model.HttpMethods.{OPTIONS}
import akka.http.scaladsl.model.HttpCharsets
import akka.http.scaladsl.model.HttpResponse
import spray.json._
import spray.json.DefaultJsonProtocol._
import WhiskWebActionsApi.MediaExtension
import RestApiCommons.{jsonPrettyResponsePrinter => jsonPrettyPrinter}
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.controller.actions.PostActionActivation
import org.apache.openwhisk.core.database._
import org.apache.openwhisk.core.entitlement.{Collection, Privilege, Resource}
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.types._
import org.apache.openwhisk.core.loadBalancer.LoadBalancerException
import org.apache.openwhisk.http.ErrorResponse.terminate
import org.apache.openwhisk.http.Messages
import org.apache.openwhisk.http.LenientSprayJsonSupport._
import org.apache.openwhisk.spi.SpiLoader
import org.apache.openwhisk.utils.JsHelpers._
import org.apache.openwhisk.core.entity.Exec

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
  def overrides(reservedParams: Set[String]): Boolean = {
    val queryParams = queryAsMap.keySet
    val bodyParams = body
      .map {
        case JsObject(fields) => fields.keySet
        case _                => Set.empty
      }
      .getOrElse(Set.empty)

    (queryParams ++ bodyParams).forall(key => !reservedParams.contains(key))
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
      user.map(u => propertyMap.namespace -> u.namespace.name.asString.toJson)
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
    val bodyParams: Map[String, JsValue] = body match {
      case Some(JsObject(fields)) if !boxQueryAndBody => fields
      case Some(v)                                    => Map(propertyMap.body -> v)
      case None if !boxQueryAndBody                   => Map.empty
      case _                                          => Map(propertyMap.body -> JsString.empty)
    }

    // precedence order is: query params -> body (last wins)
    metadata(user) ++ queryParams ++ bodyParams
  }
}

protected[core] object WhiskWebActionsApi extends Directives {

  private val mediaTranscoders = {
    // extensions are expected to contain only [a-z]
    Seq(
      MediaExtension(".http", resultAsHttp _),
      MediaExtension(".json", resultAsJson _),
      MediaExtension(".html", resultAsHtml _),
      MediaExtension(".svg", resultAsSvg _),
      MediaExtension(".text", resultAsText _))
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
   * Supported extensions and transcoder to complete a request.
   *
   * @param extension  the supported media types for action response
   * @param transcoder the HTTP decoder and terminator for the extension
   */
  protected case class MediaExtension(extension: String,
                                      transcoder: (JsValue, TransactionId, WebApiDirectives) => Route) {
    val extensionLength = extension.length
  }

  private def resultAsHtml(result: JsValue, transid: TransactionId, rp: WebApiDirectives) = {
    val htmlResult = result match {
      case JsObject(fields) => fields.get("body").orElse(fields.get("html")).getOrElse(JsNull)
      case _                => result
    }

    htmlResult match {
      case JsString(html) => complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, html))
      case _              => terminate(BadRequest, Messages.invalidMedia(`text/html`))(transid, jsonPrettyPrinter)
    }
  }

  private def resultAsSvg(result: JsValue, transid: TransactionId, rp: WebApiDirectives) = {
    val svgResult = result match {
      case JsObject(fields) => fields.get("body").orElse(fields.get("svg")).getOrElse(JsNull)
      case _                => result
    }

    svgResult match {
      case JsString(svg) => complete(HttpEntity(`image/svg+xml`, svg.getBytes))
      case _             => terminate(BadRequest, Messages.invalidMedia(`image/svg+xml`))(transid, jsonPrettyPrinter)
    }
  }

  private def resultAsText(result: JsValue, transid: TransactionId, rp: WebApiDirectives) = {
    val txtResult = result match {
      case JsObject(fields) => fields.get("body").orElse(fields.get("text"))
      case _                => Some(result)
    }

    txtResult match {
      case Some(r: JsObject)  => complete(OK, r.prettyPrint)
      case Some(r: JsArray)   => complete(OK, r.prettyPrint)
      case Some(JsString(s))  => complete(OK, s)
      case Some(JsBoolean(b)) => complete(OK, b.toString)
      case Some(JsNumber(n))  => complete(OK, n.toString)
      case Some(JsNull)       => complete(OK, JsNull.toString)
      case _                  => terminate(NotFound, Messages.propertyNotFound)(transid, jsonPrettyPrinter)
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
      } getOrElse List.empty

      val body = fields.get("body")

      val code = fields.get(rp.statusCode).map {
        case JsNumber(c) =>
          // the following throws an exception if the code is not a whole number or a valid code
          StatusCode.int2StatusCode(c.toIntExact)
        case JsString(c) =>
          // parse the string to an Int (not a BigInt) matching JsNumber case match above
          // c.toInt could throw an exception if the string isn't an integer
          StatusCode.int2StatusCode(c.toInt)

        case _ => throw new Throwable("Illegal status code")
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

  def isJsonFamily(mt: MediaType): Boolean = {
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
        // TODO: remove this extract check for base64 on json response
        // this is here for legacy reasons to not brake old webactions returning base64 json that have not migrated yet
        case nonbinary: ContentType.NonBinary if (isJsonFamily(mediaType) && Exec.isBinaryCode(str)) =>
          Try(Base64.getDecoder().decode(str)).map(HttpEntity(ct, _))
        case nonbinary: ContentType.NonBinary => Success(HttpEntity(nonbinary, str))

        // because of the default charset provided to the content type constructor
        // the remaining content types to match against are binary at this point
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

trait WhiskWebActionsApi
    extends Directives
    with ValidateRequestSize
    with PostActionActivation
    with CustomHeaders
    with CorsSettings.WebActions {
  services: WhiskServices =>

  /** API path invocation path for posting activations directly through the host. */
  protected val webInvokePathSegments: Seq[String]

  /** Mapping of HTTP request fields to action parameter names. */
  protected val webApiDirectives: WebApiDirectives

  /** Store for identities. */
  protected val authStore: AuthStore

  /** Configured authentication provider. */
  protected val authenticationProvider = SpiLoader.get[AuthenticationDirectiveProvider]

  /** The collection type for this trait. */
  protected val collection = Collection(Collection.ACTIONS)

  /** The prefix for web invokes e.g., /web. */
  private lazy val webRoutePrefix = {
    pathPrefix(webInvokePathSegments.map(_segmentStringToPathMatcher(_)).reduceLeft(_ / _))
  }

  /** Allowed verbs. */
  private lazy val allowedOperations = get | delete | post | put | head | options | patch

  private lazy val validNameSegment = pathPrefix(EntityName.REGEX.r)
  private lazy val packagePrefix = pathPrefix("default".r | EntityName.REGEX.r)

  private val defaultCorsBaseResponse =
    List(allowOrigin, allowMethods)

  private val defaultCorsWithAllowHeader = {
    defaultCorsBaseResponse :+ allowHeaders
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

  private val maxWaitForWebActionResult = Some(controllerActivationConfig.maxWaitForBlockingActivation)

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
   * Gets identity from datastore.
   * This method is factored out to allow mock testing.
   */
  protected def getIdentity(namespace: EntityName)(implicit transid: TransactionId): Future[Identity] = {
    // ask auth provider to create an identity for the given namespace
    authenticationProvider.identityByNamespace(namespace)(transid, actorSystem, authStore)
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
                onComplete(verifyWebAction(fullActionName)) {
                  case Success((actionOwnerIdentity, action)) =>
                    val actionDelegatesCors =
                      !action.annotations.getAs[Boolean](Annotations.WebCustomOptionsAnnotationName).getOrElse(false)

                    if (actionDelegatesCors) {
                      respondWithHeaders(defaultCorsResponse(context.headers)) {
                        if (context.method == OPTIONS) {
                          complete(OK, HttpEntity.Empty)
                        } else {
                          extractEntityAndProcessRequest(
                            confirmAuthenticated(action.annotations, context.headers, onBehalfOf).getOrElse(true),
                            actionOwnerIdentity,
                            action,
                            extension,
                            onBehalfOf,
                            context,
                            e)
                        }
                      }
                    } else {
                      val allowedToProceed = if (context.method != OPTIONS) {
                        confirmAuthenticated(action.annotations, context.headers, onBehalfOf).getOrElse(true)
                      } else {
                        // invoke the action for OPTIONS even if user is not authorized
                        // so that action can respond to option request
                        true
                      }

                      extractEntityAndProcessRequest(
                        allowedToProceed,
                        actionOwnerIdentity,
                        action,
                        extension,
                        onBehalfOf,
                        context,
                        e)
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
  private def verifyWebAction(actionName: FullyQualifiedEntityName)(implicit transid: TransactionId) = {

    // lookup the identity for the action namespace
    identityLookup(actionName.path.root) flatMap { actionOwnerIdentity =>
      confirmExportedAction(actionLookup(actionName)) flatMap { a =>
        checkEntitlement(actionOwnerIdentity, a) map { _ =>
          (actionOwnerIdentity, a)
        }
      }
    }
  }

  private def extractEntityAndProcessRequest(authorizedToProceed: Boolean,
                                             actionOwnerIdentity: Identity,
                                             action: WhiskActionMetaData,
                                             extension: MediaExtension,
                                             onBehalfOf: Option[Identity],
                                             context: Context,
                                             httpEntity: HttpEntity)(implicit transid: TransactionId) = {

    def process(body: Option[JsValue], isRawHttpAction: Boolean) = {
      processRequest(actionOwnerIdentity, action, extension, onBehalfOf, context.withBody(body), isRawHttpAction)
    }

    if (authorizedToProceed) {
      provide(action.annotations.getAs[Boolean](Annotations.RawHttpAnnotationName).getOrElse(false)) {
        isRawHttpAction =>
          httpEntity match {
            case Empty =>
              process(None, isRawHttpAction)

            case HttpEntity.Strict(ct, json) if WhiskWebActionsApi.isJsonFamily(ct.mediaType) && !isRawHttpAction =>
              if (json.nonEmpty) {
                entity(as[JsValue]) { body =>
                  process(Some(body), isRawHttpAction)
                }
              } else {
                process(None, isRawHttpAction)
              }

            case HttpEntity.Strict(ContentType(MediaTypes.`application/x-www-form-urlencoded`, _), _)
                if !isRawHttpAction =>
              entity(as[FormData]) { form =>
                val body = form.fields.toMap.toJson.asJsObject
                process(Some(body), isRawHttpAction)
              }

            case HttpEntity.Strict(contentType, data) =>
              // for legacy, we are encoding application/json still
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
    } else {
      terminate(Unauthorized)
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
            .overrides(webApiDirectives.reservedProperties ++ action.immutableParameters)) {
        val content = context.toActionArgument(onBehalfOf, isRawHttpAction)
        invokeAction(actionOwnerIdentity, action, Some(JsObject(content)), maxWaitForWebActionResult, cause = None)
      } else {
        Future.failed(RejectRequest(BadRequest, Messages.parametersNotAllowed))
      }
    }

    completeRequest(queuedActivation, responseType)
  }

  private def completeRequest(queuedActivation: Future[Either[ActivationId, WhiskActivation]],
                              responseType: MediaExtension)(implicit transid: TransactionId) = {
    onComplete(queuedActivation) {
      case Success(Right(activation)) =>
        respondWithActivationIdHeader(activation.activationId) {
          val result = activation.resultAsJson

          if (activation.response.isSuccess || activation.response.isApplicationError) {
            val resultPath = if (activation.response.isSuccess) {
              List.empty
            } else {
              // the activation produced an error response, so look for an error property
              // in the response, unwrap it and use it to terminate the response
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
        }

      case Success(Left(activationId)) =>
        // blocking invoke which got queued instead
        // this should not happen, instead it should be a blocking invoke timeout
        logging.debug(this, "activation waiting period expired")
        respondWithActivationIdHeader(activationId) {
          terminate(Accepted, Messages.responseNotReady)
        }

      case Failure(t: RejectRequest) => terminate(t.code, t.message)

      case Failure(t: LoadBalancerException) =>
        logging.error(this, s"failed in loadbalancer: $t")
        terminate(ServiceUnavailable)

      case Failure(t) =>
        logging.error(this, s"exception in completeRequest: $t")
        terminate(InternalServerError)
    }
  }

  /**
   * Gets the action if it exists and fail future with RejectRequest if it does not.
   *
   * @return future action document or NotFound rejection
   */
  private def actionLookup(actionName: FullyQualifiedEntityName)(
    implicit transid: TransactionId): Future[WhiskActionMetaData] = {
    WhiskActionMetaData.resolveActionAndMergeParameters(entityStore, actionName) recoverWith {
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
   * This function does not check if web action requires authentication.
   */
  private def confirmExportedAction(actionLookup: Future[WhiskActionMetaData])(
    implicit transid: TransactionId): Future[WhiskActionMetaData] = {
    actionLookup flatMap { action =>
      val isExported = action.annotations.getAs[Boolean](Annotations.WebActionAnnotationName).getOrElse(false)

      if (isExported) {
        logging.debug(this, s"${action.fullyQualifiedName(true)} is exported")
        Future.successful(action)
      } else {
        logging.debug(this, s"${action.fullyQualifiedName(true)} not exported")
        Future.failed(RejectRequest(NotFound))
      }
    }
  }

  /**
   * Checks if an action is executable.
   */
  private def checkEntitlement(identity: Identity, action: WhiskActionMetaData)(
    implicit transid: TransactionId): Future[Unit] = {

    val fqn = action.fullyQualifiedName(false)
    val resource = Resource(fqn.path, collection, Some(fqn.name.asString))
    entitlementProvider.check(identity, Privilege.ACTIVATE, resource)
  }

  /**
   * Checks if an action requires authenticate and is authenticated (i.e., carries the required annotation).
   * This function assumes the action is a web action.
   *
   * @param annotations the web action annotations
   * @param reqHeaders the web action invocation request headers
   * @param authenticatedUser true if this request is from an authenticated whisk user
   * @return None if web annotation does not specify an authentication scheme
   *         Some(true) if web annotation includes require-whisk-auth and value matches the request header `X-Require-Whisk-Auth` value
   *         Some(true) if web annotation requires an authenticated whisk user and that user has already authenticated
   *         Some(false) if web annotation includes require-whisk-auth and the request does not include the header `X-Require-Whisk-Auth`
   *         Some(false) if web annotation includes require-whisk-auth and its value does not match the request header `X-Require-Whisk-Auth` value
   */
  private def confirmAuthenticated(annotations: Parameters,
                                   reqHeaders: Seq[HttpHeader],
                                   authenticatedUser: Option[Identity]): Option[Boolean] = {
    def checkAuthHeader(expected: String): Boolean = {
      reqHeaders.find(_.is(WhiskAction.requireWhiskAuthHeader)).map(_.value == expected).getOrElse(false)
    }

    annotations
      .get(Annotations.RequireWhiskAuthAnnotation)
      .map {
        case JsString(auth)             => checkAuthHeader(auth) // allowed if auth matches header
        case JsNumber(auth)             => checkAuthHeader(auth.toString) // allowed if auth matches header
        case JsTrue | JsBoolean(true)   => authenticatedUser.isDefined // allowed if user already authenticated
        case JsFalse | JsBoolean(false) => true // allowed if the require-whisk-auth is specified as false
        case _                          => false // not allowed, something is not right
      }
  }

}

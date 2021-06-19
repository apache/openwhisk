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

import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.server.directives.AuthenticationDirective
import akka.http.scaladsl.server.{Directives, Route}
import pureconfig._
import pureconfig.generic.auto._
import spray.json.DefaultJsonProtocol._
import spray.json._
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.containerpool.logging.LogStore
import org.apache.openwhisk.core.database.{ActivationStore, CacheChangeNotification}
import org.apache.openwhisk.core.entitlement._
import org.apache.openwhisk.core.entity.ActivationId.ActivationIdGenerator
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.types._
import org.apache.openwhisk.core.loadBalancer.LoadBalancer
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import org.apache.openwhisk.http.Messages
import org.apache.openwhisk.spi.{Spi, SpiLoader}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * Abstract class which provides basic Directives which are used to construct route structures
 * which are common to all versions of the Rest API.
 */
protected[controller] class SwaggerDocs(apipath: Uri.Path, doc: String)(implicit actorSystem: ActorSystem)
    extends Directives {
  case class SwaggerConfig(fileSystem: Boolean, dirPath: String)

  /** Swagger end points. */
  protected val swaggeruipath = "docs"
  protected val swaggerdocpath = "api-docs"
  private val swaggerConfig = loadConfigOrThrow[SwaggerConfig](ConfigKeys.swaggerUi)

  def basepath(url: Uri.Path = apipath): String = {
    (if (url.startsWithSlash) url else Uri.Path./(url)).toString
  }

  /**
   * Defines the routes to serve the swagger docs.
   */
  val swaggerRoutes: Route = {
    pathPrefix(swaggeruipath) {
      if (swaggerConfig.fileSystem) getFromDirectory(swaggerConfig.dirPath)
      else getFromResourceDirectory(swaggerConfig.dirPath)
    } ~ path(swaggeruipath) {
      redirect(s"$swaggeruipath/index.html?url=$apiDocsUrl", PermanentRedirect)
    } ~ pathPrefix(swaggerdocpath) {
      pathEndOrSingleSlash {
        getFromResource(doc)
      }
    }
  }

  /** Forces add leading slash for swagger api-doc url rewrite to work. */
  private def apiDocsUrl = basepath(apipath / swaggerdocpath)
}

protected[controller] object RestApiCommons {
  def requiredProperties =
    Map(WhiskConfig.servicePort -> 8080.toString) ++
      EntitlementProvider.requiredProperties ++
      WhiskActionsApi.requiredProperties

  import akka.http.scaladsl.model.HttpCharsets
  import akka.http.scaladsl.model.MediaTypes.`application/json`
  import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}

  /**
   * Extract an empty entity into a JSON object. This is useful for the
   * main APIs which accept JSON content type by default but may accept
   * no entity in the request.
   */
  implicit val emptyEntityToJsObject: FromEntityUnmarshaller[JsObject] = {
    Unmarshaller.byteStringUnmarshaller.forContentTypes(`application/json`).mapWithCharset { (data, charset) =>
      if (data.size == 0) {
        JsObject.empty
      } else {
        val input = {
          if (charset == HttpCharsets.`UTF-8`) ParserInput(data.toArray)
          else ParserInput(data.decodeString(charset.nioCharset))
        }

        JsonParser(input).asJsObject
      }
    }
  }

  /** Custom unmarshaller for query parameters "limit" for "list" operations. */
  case class ListLimit(n: Int)

  def stringToListLimit(collection: Collection): Unmarshaller[String, ListLimit] = {
    Unmarshaller.strict[String, ListLimit] { value =>
      Try { value.toInt } match {
        case Success(n) if (n == 0)                                  => ListLimit(Collection.MAX_LIST_LIMIT)
        case Success(n) if (n > 0 && n <= Collection.MAX_LIST_LIMIT) => ListLimit(n)
        case Success(n) =>
          throw new IllegalArgumentException(
            Messages.listLimitOutOfRange(collection.path, n, Collection.MAX_LIST_LIMIT))
        case Failure(t) => throw new IllegalArgumentException(Messages.argumentNotInteger(collection.path, value))
      }
    }
  }

  /** Custom unmarshaller for query parameters "skip" for "list" operations. */
  case class ListSkip(n: Int)

  def stringToListSkip(collection: Collection): Unmarshaller[String, ListSkip] = {
    Unmarshaller.strict[String, ListSkip] { value =>
      Try { value.toInt } match {
        case Success(n) if (n >= 0) => ListSkip(n)
        case Success(n) =>
          throw new IllegalArgumentException(Messages.listSkipOutOfRange(collection.path, n))
        case Failure(t) => throw new IllegalArgumentException(Messages.argumentNotInteger(collection.path, value))
      }
    }
  }

  /** Pretty print JSON response. */
  implicit val jsonPrettyResponsePrinter = PrettyPrinter

  /** Standard compact JSON printer. */
  implicit val jsonDefaultResponsePrinter = CompactPrinter
}

/**
 * A trait for wrapping routes with headers to include in response.
 * Useful for CORS.
 */
protected[controller] trait RespondWithHeaders extends Directives with CorsSettings.RestAPIs {
  val sendCorsHeaders = respondWithHeaders(allowOrigin, allowHeaders, allowMethods)
}

case class WhiskInformation(buildNo: String, date: String)

class RestAPIVersion(config: WhiskConfig, apiPath: String, apiVersion: String)(
  implicit val activeAckTopicIndex: ControllerInstanceId,
  implicit val actorSystem: ActorSystem,
  implicit val logging: Logging,
  implicit val entityStore: EntityStore,
  implicit val entitlementProvider: EntitlementProvider,
  implicit val activationIdFactory: ActivationIdGenerator,
  implicit val loadBalancer: LoadBalancer,
  implicit val cacheChangeNotification: Some[CacheChangeNotification],
  implicit val activationStore: ActivationStore,
  implicit val logStore: LogStore,
  implicit val whiskConfig: WhiskConfig)
    extends SwaggerDocs(Uri.Path(apiPath) / apiVersion, "apiv1swagger.json")
    with RespondWithHeaders {
  implicit val executionContext = actorSystem.dispatcher
  implicit val authStore = WhiskAuthStore.datastore()
  val whiskInfo = loadConfigOrThrow[WhiskInformation](ConfigKeys.buildInformation)

  private implicit val authenticationDirectiveProvider =
    SpiLoader.get[AuthenticationDirectiveProvider]

  def prefix = pathPrefix(apiPath / apiVersion)

  /**
   * Describes details of a particular API path.
   */
  val info = (pathEndOrSingleSlash & get) {
    complete(
      JsObject(
        "description" -> "OpenWhisk API".toJson,
        "api_version" -> SemVer(1, 0, 0).toJson,
        "api_version_path" -> apiVersion.toJson,
        "build" -> whiskInfo.date.toJson,
        "buildno" -> whiskInfo.buildNo.toJson,
        "swagger_paths" -> JsObject("ui" -> s"/$swaggeruipath".toJson, "api-docs" -> s"/$swaggerdocpath".toJson)))
  }

  def routes(implicit transid: TransactionId): Route = {
    prefix {
      sendCorsHeaders {
        info ~
          authenticationDirectiveProvider.authenticate(transid, authStore, logging) { user =>
            namespaces.routes(user) ~
              pathPrefix(Collection.NAMESPACES) {
                actions.routes(user) ~
                  triggers.routes(user) ~
                  rules.routes(user) ~
                  activations.routes(user) ~
                  packages.routes(user) ~
                  limits.routes(user)
              }
          } ~
          swaggerRoutes
      } ~ {
        // web actions are distinct to separate the cors header
        // and allow the actions themselves to respond to options
        authenticationDirectiveProvider.authenticate(transid, authStore, logging) { user =>
          web.routes(user)
        } ~ {
          web.routes()
        } ~
          options {
            sendCorsHeaders {
              complete(OK)
            }
          }
      }
    }
  }

  private val namespaces = new NamespacesApi(apiPath, apiVersion)
  private val actions = new ActionsApi(apiPath, apiVersion)
  private val packages = new PackagesApi(apiPath, apiVersion)
  private val triggers = new TriggersApi(apiPath, apiVersion)
  private val activations = new ActivationsApi(apiPath, apiVersion)
  private val rules = new RulesApi(apiPath, apiVersion)
  private val limits = new LimitsApi(apiPath, apiVersion)
  private val web = new WebActionsApi(Seq("web"), new WebApiDirectives())

  class NamespacesApi(val apiPath: String, val apiVersion: String) extends WhiskNamespacesApi

  class LimitsApi(val apiPath: String, val apiVersion: String)(
    implicit override val entitlementProvider: EntitlementProvider,
    override val executionContext: ExecutionContext,
    override val whiskConfig: WhiskConfig)
      extends WhiskLimitsApi

  class ActionsApi(val apiPath: String, val apiVersion: String)(
    implicit override val actorSystem: ActorSystem,
    override val activeAckTopicIndex: ControllerInstanceId,
    override val entityStore: EntityStore,
    override val activationStore: ActivationStore,
    override val entitlementProvider: EntitlementProvider,
    override val activationIdFactory: ActivationIdGenerator,
    override val loadBalancer: LoadBalancer,
    override val cacheChangeNotification: Some[CacheChangeNotification],
    override val executionContext: ExecutionContext,
    override val logging: Logging,
    override val whiskConfig: WhiskConfig)
      extends WhiskActionsApi
      with WhiskServices {
    logging.info(this, s"actionSequenceLimit '${whiskConfig.actionSequenceLimit}'")(TransactionId.controller)
    assert(whiskConfig.actionSequenceLimit.toInt > 0)
  }

  class ActivationsApi(val apiPath: String, val apiVersion: String)(
    implicit override val activationStore: ActivationStore,
    override val logStore: LogStore,
    override val entitlementProvider: EntitlementProvider,
    override val executionContext: ExecutionContext,
    override val logging: Logging)
      extends WhiskActivationsApi

  class PackagesApi(val apiPath: String, val apiVersion: String)(
    implicit override val entityStore: EntityStore,
    override val entitlementProvider: EntitlementProvider,
    override val activationIdFactory: ActivationIdGenerator,
    override val loadBalancer: LoadBalancer,
    override val cacheChangeNotification: Some[CacheChangeNotification],
    override val executionContext: ExecutionContext,
    override val logging: Logging,
    override val whiskConfig: WhiskConfig)
      extends WhiskPackagesApi
      with WhiskServices

  class RulesApi(val apiPath: String, val apiVersion: String)(
    implicit override val actorSystem: ActorSystem,
    override val entityStore: EntityStore,
    override val entitlementProvider: EntitlementProvider,
    override val activationIdFactory: ActivationIdGenerator,
    override val loadBalancer: LoadBalancer,
    override val cacheChangeNotification: Some[CacheChangeNotification],
    override val executionContext: ExecutionContext,
    override val logging: Logging,
    override val whiskConfig: WhiskConfig)
      extends WhiskRulesApi
      with WhiskServices

  class TriggersApi(val apiPath: String, val apiVersion: String)(
    implicit override val actorSystem: ActorSystem,
    implicit override val entityStore: EntityStore,
    override val entitlementProvider: EntitlementProvider,
    override val activationStore: ActivationStore,
    override val activationIdFactory: ActivationIdGenerator,
    override val loadBalancer: LoadBalancer,
    override val cacheChangeNotification: Some[CacheChangeNotification],
    override val executionContext: ExecutionContext,
    override val logging: Logging,
    override val whiskConfig: WhiskConfig)
      extends WhiskTriggersApi
      with WhiskServices

  protected[controller] class WebActionsApi(override val webInvokePathSegments: Seq[String],
                                            override val webApiDirectives: WebApiDirectives)(
    implicit override val authStore: AuthStore,
    implicit val entityStore: EntityStore,
    override val activeAckTopicIndex: ControllerInstanceId,
    override val activationStore: ActivationStore,
    override val entitlementProvider: EntitlementProvider,
    override val activationIdFactory: ActivationIdGenerator,
    override val loadBalancer: LoadBalancer,
    override val actorSystem: ActorSystem,
    override val executionContext: ExecutionContext,
    override val logging: Logging,
    override val whiskConfig: WhiskConfig)
      extends WhiskWebActionsApi
      with WhiskServices
}

trait AuthenticationDirectiveProvider extends Spi {

  /**
   * Returns an authentication directive used to validate the
   * passed user credentials.
   * At runtime the directive returns an user identity
   * which is passed to the following routes.
   *
   * @return authentication directive used to verify the user credentials
   */
  def authenticate(implicit transid: TransactionId,
                   authStore: AuthStore,
                   logging: Logging): AuthenticationDirective[Identity]

  /**
   * Retrieves an Identity based on a given namespace name.
   *
   * For use-cases of anonymous invocation (i.e. WebActions),
   * we need to an identity based on a given namespace-name to
   * give the invocation all the context needed.
   *
   * @param namespace the namespace that the identity will be based on
   * @return identity based on the given namespace
   */
  def identityByNamespace(
    namespace: EntityName)(implicit transid: TransactionId, system: ActorSystem, authStore: AuthStore): Future[Identity]
}

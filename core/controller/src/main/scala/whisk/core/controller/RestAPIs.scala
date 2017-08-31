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

import scala.concurrent.ExecutionContext

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.stream.ActorMaterializer

import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.database.CacheChangeNotification
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.whiskVersionBuildno
import whisk.core.WhiskConfig.whiskVersionDate
import whisk.core.entitlement._
import whisk.core.entity._
import whisk.core.entity.ActivationId.ActivationIdGenerator
import whisk.core.entity.WhiskAuthStore
import whisk.core.entity.types._
import whisk.core.loadBalancer.LoadBalancerService

/**
 * Abstract class which provides basic Directives which are used to construct route structures
 * which are common to all versions of the Rest API.
 */
protected[controller] class SwaggerDocs(apipath: Uri.Path, doc: String)(implicit actorSystem: ActorSystem)
    extends Directives {

    /** Swagger end points. */
    protected val swaggeruipath = "docs"
    protected val swaggerdocpath = "api-docs"

    def basepath(url: Uri.Path = apipath): String = {
        (if (url.startsWithSlash) url else Uri.Path./(url)).toString
    }

    /**
     * Defines the routes to serve the swagger docs.
     */
    val swaggerRoutes: Route = {
        pathPrefix(swaggeruipath) {
            getFromDirectory("/swagger-ui/")
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
    def requiredProperties = Map(WhiskConfig.servicePort -> 8080.toString) ++
        WhiskConfig.whiskVersion ++
        WhiskAuthStore.requiredProperties ++
        WhiskEntityStore.requiredProperties ++
        WhiskActivationStore.requiredProperties ++
        EntitlementProvider.requiredProperties ++
        WhiskActionsApi.requiredProperties ++
        Authenticate.requiredProperties ++
        Collection.requiredProperties

    import akka.http.scaladsl.model.HttpCharsets
    import akka.http.scaladsl.model.MediaTypes.`application/json`
    import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
    import akka.http.scaladsl.unmarshalling.Unmarshaller

    /**
     * Extract an empty entity into a JSON object. This is useful for the
     * main APIs which accept JSON content type by default but may accept
     * no entity in the request.
     */
    implicit val emptyEntityToJsObject: FromEntityUnmarshaller[JsObject] = {
        Unmarshaller.byteStringUnmarshaller.forContentTypes(`application/json`).mapWithCharset { (data, charset) =>
            if (data.size == 0) {
                JsObject()
            } else {
                val input = {
                    if (charset == HttpCharsets.`UTF-8`) ParserInput(data.toArray)
                    else ParserInput(data.decodeString(charset.nioCharset))
                }

                JsonParser(input).asJsObject
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
protected[controller] trait RespondWithHeaders extends Directives {
    val allowOrigin = `Access-Control-Allow-Origin`.*
    val allowHeaders = `Access-Control-Allow-Headers`("Authorization", "Content-Type")
    val sendCorsHeaders = respondWithHeaders(allowOrigin, allowHeaders)
}

class RestAPIVersion(config: WhiskConfig, apiPath: String, apiVersion: String)(
    implicit val activeAckTopicIndex: InstanceId,
    implicit val actorSystem: ActorSystem,
    implicit val materializer: ActorMaterializer,
    implicit val logging: Logging,
    implicit val entityStore: EntityStore,
    implicit val entitlementProvider: EntitlementProvider,
    implicit val activationIdFactory: ActivationIdGenerator,
    implicit val loadBalancer: LoadBalancerService,
    implicit val cacheChangeNotification: Some[CacheChangeNotification],
    implicit val activationStore: ActivationStore,
    implicit val whiskConfig: WhiskConfig)
    extends SwaggerDocs(Uri.Path(apiPath) / apiVersion, "apiv1swagger.json")
    with Authenticate
    with AuthenticatedRoute
    with RespondWithHeaders {
    implicit val executionContext = actorSystem.dispatcher
    implicit val authStore = WhiskAuthStore.datastore(config)

    def prefix = pathPrefix(apiPath / apiVersion)

    /**
     * Describes details of a particular API path.
     */
    val info = (pathEndOrSingleSlash & get) {
        complete(JsObject(
            "description" -> "OpenWhisk API".toJson,
            "api_version" -> SemVer(1, 0, 0).toJson,
            "api_version_path" -> apiVersion.toJson,
            "build" -> whiskConfig(whiskVersionDate).toJson,
            "buildno" -> whiskConfig(whiskVersionBuildno).toJson,
            "swagger_paths" -> JsObject(
                "ui" -> s"/$swaggeruipath".toJson,
                "api-docs" -> s"/$swaggerdocpath".toJson)))
    }

    def routes(implicit transid: TransactionId): Route = {
        prefix {
            sendCorsHeaders {
                info ~ basicAuth(validateCredentials) { user =>
                    namespaces.routes(user) ~
                    pathPrefix(Collection.NAMESPACES) {
                        actions.routes(user) ~
                        triggers.routes(user) ~
                        rules.routes(user) ~
                        activations.routes(user) ~
                        packages.routes(user)
                    }
                } ~ {
                    swaggerRoutes
                }
            } ~ {
                // web actions are distinct to separate the cors header
                // and allow the actions themselves to respond to options
                basicAuth(validateCredentials) { user =>
                    web.routes(user)
                } ~ {
                    web.routes()
                } ~ options {
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
    private val WebApiDirectives = new WebApiDirectives()
    private val web = new WebActionsApi(Seq("web"), this.WebApiDirectives)

    class NamespacesApi(
        val apiPath: String,
        val apiVersion: String)(
        implicit override val entityStore: EntityStore,
        override val entitlementProvider: EntitlementProvider,
        override val executionContext: ExecutionContext,
        override val logging: Logging)
    extends WhiskNamespacesApi

    class ActionsApi(
        val apiPath: String,
        val apiVersion: String)(
        implicit override val actorSystem: ActorSystem,
        override val activeAckTopicIndex: InstanceId,
        override val entityStore: EntityStore,
        override val activationStore: ActivationStore,
        override val entitlementProvider: EntitlementProvider,
        override val activationIdFactory: ActivationIdGenerator,
        override val loadBalancer: LoadBalancerService,
        override val cacheChangeNotification: Some[CacheChangeNotification],
        override val executionContext: ExecutionContext,
        override val logging: Logging,
        override val whiskConfig: WhiskConfig)
    extends WhiskActionsApi with WhiskServices {
        logging.info(this, s"actionSequenceLimit '${whiskConfig.actionSequenceLimit}'")
        assert(whiskConfig.actionSequenceLimit.toInt > 0)
    }

    class ActivationsApi(
        val apiPath: String,
        val apiVersion: String)(
        implicit override val activationStore: ActivationStore,
        override val entitlementProvider: EntitlementProvider,
        override val executionContext: ExecutionContext,
        override val logging: Logging)
    extends WhiskActivationsApi

    class PackagesApi(
        val apiPath: String,
        val apiVersion: String)(
        implicit override val entityStore: EntityStore,
        override val entitlementProvider: EntitlementProvider,
        override val activationIdFactory: ActivationIdGenerator,
        override val loadBalancer: LoadBalancerService,
        override val cacheChangeNotification: Some[CacheChangeNotification],
        override val executionContext: ExecutionContext,
        override val logging: Logging,
        override val whiskConfig: WhiskConfig)
    extends WhiskPackagesApi with WhiskServices

    class RulesApi(
        val apiPath: String,
        val apiVersion: String)(
        implicit override val actorSystem: ActorSystem,
        override val entityStore: EntityStore,
        override val entitlementProvider: EntitlementProvider,
        override val activationIdFactory: ActivationIdGenerator,
        override val loadBalancer: LoadBalancerService,
        override val cacheChangeNotification: Some[CacheChangeNotification],
        override val executionContext: ExecutionContext,
        override val logging: Logging,
        override val whiskConfig: WhiskConfig)
    extends WhiskRulesApi with WhiskServices

    class TriggersApi(
        val apiPath: String,
        val apiVersion: String)(
        implicit override val actorSystem: ActorSystem,
        implicit override val entityStore: EntityStore,
        override val entitlementProvider: EntitlementProvider,
        override val activationStore: ActivationStore,
        override val activationIdFactory: ActivationIdGenerator,
        override val loadBalancer: LoadBalancerService,
        override val cacheChangeNotification: Some[CacheChangeNotification],
        override val executionContext: ExecutionContext,
        override val logging: Logging,
        override val whiskConfig: WhiskConfig,
        override val materializer: ActorMaterializer)
    extends WhiskTriggersApi with WhiskServices

    protected[controller] class WebActionsApi(
        override val webInvokePathSegments: Seq[String],
        override val webApiDirectives: WebApiDirectives)(
        implicit override val authStore: AuthStore,
        implicit val entityStore: EntityStore,
        override val activeAckTopicIndex: InstanceId,
        override val activationStore: ActivationStore,
        override val entitlementProvider: EntitlementProvider,
        override val activationIdFactory: ActivationIdGenerator,
        override val loadBalancer: LoadBalancerService,
        override val actorSystem: ActorSystem,
        override val executionContext: ExecutionContext,
        override val logging: Logging,
        override val whiskConfig: WhiskConfig)
    extends WhiskWebActionsApi with WhiskServices
}

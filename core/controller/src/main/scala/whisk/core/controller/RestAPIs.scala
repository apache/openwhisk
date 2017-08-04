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
import RestApiCommons._
import akka.actor.ActorSystem
import spray.http.AllOrigins
import spray.http.HttpHeaders._
import spray.http.StatusCodes._
import spray.http.Uri
import spray.httpx.SprayJsonSupport._
import spray.json._
import spray.json.DefaultJsonProtocol._
import spray.routing.Directive.pimpApply
import spray.routing.Directives
import spray.routing.Route
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.whiskVersionBuildno
import whisk.core.WhiskConfig.whiskVersionDate
import whisk.core.entitlement._
import whisk.core.entity._
import whisk.core.entity.ActivationId.ActivationIdGenerator
import whisk.core.entity.types._
import whisk.core.loadBalancer.LoadBalancer

/**
 * Abstract class which provides basic Directives which are used to construct route structures
 * which are common to all versions of the Rest API.
 */
protected[controller] class SwaggerDocs(
    apipath: Uri.Path,
    doc: String)(
        implicit actorSystem: ActorSystem)
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

/**
 * A singleton object which defines properties needed to instantiate a service for v1 or v2
 * of the REST API.
 */
protected[controller] object RestApiCommons {
    def requiredProperties =
        WhiskConfig.whiskVersion ++
            WhiskAuthStore.requiredProperties ++
            WhiskEntityStore.requiredProperties ++
            WhiskActivationStore.requiredProperties ++
            EntitlementProvider.requiredProperties ++
            WhiskActionsApi.requiredProperties ++
            Authenticate.requiredProperties ++
            Collection.requiredProperties

    /**
     * The web actions API is available in both v1 and v2.
     * It handles web actions.
     */
    protected[controller] class WebActionsApi(
        override val webInvokePathSegments: Seq[String],
        override val webApiDirectives: WebApiDirectives)(
            implicit override val authStore: AuthStore,
            implicit val entityStore: EntityStore,
            override val activeAckTopicIndex: InstanceId,
            override val activationStore: ActivationStore,
            override val entitlementProvider: EntitlementProvider,
            override val activationIdFactory: ActivationIdGenerator,
            override val loadBalancer: LoadBalancer,
            override val actorSystem: ActorSystem,
            override val executionContext: ExecutionContext,
            override val logging: Logging,
            override val whiskConfig: WhiskConfig)
        extends WhiskWebActionsApi with WhiskServices
}

/**
 * A trait for wrapping routes with headers to include in response.
 * Useful for CORS.
 */
protected[controller] trait RespondWithHeaders extends Directives {
    val allowOrigin = `Access-Control-Allow-Origin`(AllOrigins)
    val allowHeaders = `Access-Control-Allow-Headers`("Authorization", "Content-Type")

    val sendCorsHeaders = respondWithHeaders(allowOrigin, allowHeaders)
}

/**
 * An object which creates the Routes that define v1 of the whisk REST API.
 */
protected[controller] class RestAPIVersion(apipath: String, apiversion: String)(
    implicit val activeAckTopicIndex: InstanceId,
    implicit val authStore: AuthStore,
    implicit val entityStore: EntityStore,
    implicit val activationStore: ActivationStore,
    implicit val entitlementProvider: EntitlementProvider,
    implicit val activationIdFactory: ActivationIdGenerator,
    implicit val loadBalancer: LoadBalancer,
    implicit val actorSystem: ActorSystem,
    implicit val executionContext: ExecutionContext,
    implicit val logging: Logging,
    implicit val whiskConfig: WhiskConfig)
    extends SwaggerDocs(Uri.Path(apipath) / apiversion, "apiv1swagger.json")
    with Authenticate
    with AuthenticatedRoute
    with RespondWithHeaders {

    /**
     * Here is the key method: it defines the Route (route tree) which implement v1 of the REST API.
     *
     * @Idioglossia This relies on the spray routing DSL.
     * @see http://spray.io/documentation/1.2.2/spray-routing/
     */
    def routes(implicit transid: TransactionId): Route = {
        pathPrefix(apipath / apiversion) {
            sendCorsHeaders {
                (pathEndOrSingleSlash & get) {
                    complete(OK, info)
                } ~ authenticate(basicauth) {
                    user =>
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
                authenticate(basicauth) { user =>
                    web.routes(user) ~ webexp.routes(user)
                } ~ {
                    web.routes() ~ webexp.routes()
                } ~ options {
                    sendCorsHeaders {
                        complete(OK)
                    }
                }
            }
        }
    }

    private val namespaces = new NamespacesApi(apipath, apiversion)
    private val actions = new ActionsApi(apipath, apiversion)
    private val triggers = new TriggersApi(apipath, apiversion)
    private val rules = new RulesApi(apipath, apiversion)
    private val activations = new ActivationsApi(apipath, apiversion)
    private val packages = new PackagesApi(apipath, apiversion)
    private val webexp = new WebActionsApi(Seq("experimental", "web"), WebApiDirectives.exp)
    private val web = new WebActionsApi(Seq("web"), WebApiDirectives.web)

    /**
     * Describes details of a particular API path.
     */
    private val info = JsObject(
        "description" -> "OpenWhisk API".toJson,
        "api_version" -> SemVer(1, 0, 0).toJson,
        "api_version_path" -> apiversion.toJson,
        "build" -> whiskConfig(whiskVersionDate).toJson,
        "buildno" -> whiskConfig(whiskVersionBuildno).toJson,
        "swagger_paths" -> JsObject(
            "ui" -> s"/$swaggeruipath".toJson,
            "api-docs" -> s"/$swaggerdocpath".toJson))

    class NamespacesApi(
        val apipath: String,
        val apiversion: String)(
            implicit override val entityStore: EntityStore,
            override val entitlementProvider: EntitlementProvider,
            override val executionContext: ExecutionContext,
            override val logging: Logging)
        extends WhiskNamespacesApi

    class ActionsApi(
        val apipath: String,
        val apiversion: String)(
            implicit override val actorSystem: ActorSystem,
            override val activeAckTopicIndex: InstanceId,
            override val entityStore: EntityStore,
            override val activationStore: ActivationStore,
            override val entitlementProvider: EntitlementProvider,
            override val activationIdFactory: ActivationIdGenerator,
            override val loadBalancer: LoadBalancer,
            override val executionContext: ExecutionContext,
            override val logging: Logging,
            override val whiskConfig: WhiskConfig)
        extends WhiskActionsApi with WhiskServices {
        logging.info(this, s"actionSequenceLimit '${whiskConfig.actionSequenceLimit}'")
        assert(whiskConfig.actionSequenceLimit.toInt > 0)
    }

    class TriggersApi(
        val apipath: String,
        val apiversion: String)(
            implicit override val actorSystem: ActorSystem,
            implicit override val entityStore: EntityStore,
            override val entitlementProvider: EntitlementProvider,
            override val activationStore: ActivationStore,
            override val activationIdFactory: ActivationIdGenerator,
            override val loadBalancer: LoadBalancer,
            override val executionContext: ExecutionContext,
            override val logging: Logging,
            override val whiskConfig: WhiskConfig)
        extends WhiskTriggersApi with WhiskServices

    class RulesApi(
        val apipath: String,
        val apiversion: String)(
            implicit override val actorSystem: ActorSystem,
            override val entityStore: EntityStore,
            override val entitlementProvider: EntitlementProvider,
            override val activationIdFactory: ActivationIdGenerator,
            override val loadBalancer: LoadBalancer,
            override val executionContext: ExecutionContext,
            override val logging: Logging,
            override val whiskConfig: WhiskConfig)
        extends WhiskRulesApi with WhiskServices

    class ActivationsApi(
        val apipath: String,
        val apiversion: String)(
            implicit override val activationStore: ActivationStore,
            override val entitlementProvider: EntitlementProvider,
            override val executionContext: ExecutionContext,
            override val logging: Logging)
        extends WhiskActivationsApi

    class PackagesApi(
        val apipath: String,
        val apiversion: String)(
            implicit override val entityStore: EntityStore,
            override val entitlementProvider: EntitlementProvider,
            override val activationIdFactory: ActivationIdGenerator,
            override val loadBalancer: LoadBalancer,
            override val executionContext: ExecutionContext,
            override val logging: Logging,
            override val whiskConfig: WhiskConfig)
        extends WhiskPackagesApi with WhiskServices
}

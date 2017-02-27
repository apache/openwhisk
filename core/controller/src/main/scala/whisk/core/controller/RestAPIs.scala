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

import scala.concurrent.ExecutionContext

import akka.actor.ActorSystem
import spray.http.AllOrigins
import spray.http.HttpHeaders.`Access-Control-Allow-Headers`
import spray.http.HttpHeaders.`Access-Control-Allow-Origin`
import spray.http.StatusCodes._
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
import whisk.core.loadBalancer.LoadBalancerService

/**
 * Abstract class which provides basic Directives which are used to construct route structures
 * which are common to all versions of the Rest API.
 */
abstract protected[controller] class RestAPIVersion(
    protected val apiversion: String,
    protected val build: String,
    protected val buildno: String)
    extends Directives {

    /** Base API prefix. */
    protected val apipath = "api"

    /** Swagger end points. */
    protected val swaggeruipath = "docs"
    protected val swaggerdocpath = "api-docs"

    def prefix = pathPrefix(apipath / apiversion)

    /**
     * This is the most important method -- it provides the routes that define the REST API.
     */
    def routes(implicit transid: TransactionId): Route

    /**
     * Information which describes details of a particular deployment of the REST API.
     */
    def info = {
        JsObject(
            "openwhisk" -> "hello".toJson,
            "version" -> apiversion.toJson,
            "build" -> build.toJson,
            "buildno" -> buildno.toJson)
    }
}

/**
 * A singleton object which defines properties needed to instantiate a service for v1
 * of the REST API.
 */
protected[controller] object RestAPIVersion_v1 {
    def requiredProperties =
        WhiskConfig.whiskVersion ++
            WhiskAuthStore.requiredProperties ++
            WhiskEntityStore.requiredProperties ++
            WhiskActivationStore.requiredProperties ++
            WhiskConfig.consulServer ++
            EntitlementProvider.requiredProperties ++
            WhiskActionsApi.requiredProperties ++
            Authenticate.requiredProperties ++
            Collection.requiredProperties
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
protected[controller] class RestAPIVersion_v1(
    config: WhiskConfig,
    implicit val actorSystem: ActorSystem,
    implicit val logging: Logging)
    extends RestAPIVersion("v1", config(whiskVersionDate), config(whiskVersionBuildno))
    with Authenticate
    with AuthenticatedRoute
    with RespondWithHeaders {

    implicit val executionContext = actorSystem.dispatcher

    /**
     * Here is the key method: it defines the Route (route tree) which implement v1 of the REST API.
     *
     * @Idioglossia This relies on the spray routing DSL.
     * @see http://spray.io/documentation/1.2.2/spray-routing/
     */
    override def routes(implicit transid: TransactionId): Route = {
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
                            } ~ meta.routes(user)
                } ~ {
                    meta.routes()
                } ~ pathPrefix(swaggeruipath) {
                    getFromDirectory("/swagger-ui/")
                } ~ path(swaggeruipath) {
                    redirect(s"$swaggeruipath/index.html", PermanentRedirect)
                } ~ path(swaggerdocpath) {
                    getFromResource("whiskswagger.json")
                } ~ options {
                    complete(OK)
                }
            }
        } ~ internalInvokerHealth
    }

    // initialize datastores
    protected implicit val authStore = WhiskAuthStore.datastore(config)
    protected implicit val entityStore = WhiskEntityStore.datastore(config)
    protected implicit val activationStore = WhiskActivationStore.datastore(config)

    // initialize backend services
    protected implicit val consulServer = config.consulServer
    protected implicit val loadBalancer = new LoadBalancerService(config)
    protected implicit val entitlementService = new LocalEntitlementProvider(config, loadBalancer)
    protected implicit val activationId = new ActivationIdGenerator {}

    // register collections and set verbosities on datastores and backend services
    Collection.initialize(entityStore)

    private val namespaces = new NamespacesApi(apipath, apiversion)
    private val actions = new ActionsApi(apipath, apiversion)
    private val triggers = new TriggersApi(apipath, apiversion)
    private val rules = new RulesApi(apipath, apiversion)
    private val activations = new ActivationsApi(apipath, apiversion)
    private val packages = new PackagesApi(apipath, apiversion)
    private val meta = new MetasApi(apipath, apiversion)

    class NamespacesApi(
        val apipath: String,
        val apiversion: String)(
            implicit override val entityStore: EntityStore,
            override val entitlementProvider: EntitlementProvider,
            override val executionContext: ExecutionContext,
            override val logging: Logging)
        extends WhiskNamespacesApi {
    }

    class ActionsApi(
        val apipath: String,
        val apiversion: String)(
            implicit override val actorSystem: ActorSystem,
            override val entityStore: EntityStore,
            override val activationStore: ActivationStore,
            override val entitlementProvider: EntitlementProvider,
            override val activationIdFactory: ActivationIdGenerator,
            override val loadBalancer: LoadBalancerService,
            override val consulServer: String,
            override val executionContext: ExecutionContext,
            override val logging: Logging)
        extends WhiskActionsApi with WhiskServices {
        override val whiskConfig = config
        logging.info(this, s"actionSequenceLimit '${config.actionSequenceLimit}'")
        assert(config.actionSequenceLimit.toInt > 0)
    }

    class TriggersApi(
        val apipath: String,
        val apiversion: String)(
            implicit override val actorSystem: ActorSystem,
            implicit override val entityStore: EntityStore,
            override val entitlementProvider: EntitlementProvider,
            override val activationStore: ActivationStore,
            override val activationIdFactory: ActivationIdGenerator,
            override val loadBalancer: LoadBalancerService,
            override val consulServer: String,
            override val executionContext: ExecutionContext,
            override val logging: Logging)
        extends WhiskTriggersApi with WhiskServices {
        override val whiskConfig = config
    }

    class RulesApi(
        val apipath: String,
        val apiversion: String)(
            implicit override val actorSystem: ActorSystem,
            override val entityStore: EntityStore,
            override val entitlementProvider: EntitlementProvider,
            override val activationIdFactory: ActivationIdGenerator,
            override val loadBalancer: LoadBalancerService,
            override val consulServer: String,
            override val executionContext: ExecutionContext,
            override val logging: Logging)
        extends WhiskRulesApi with WhiskServices {
        override val whiskConfig = config
    }

    class ActivationsApi(
        val apipath: String,
        val apiversion: String)(
            implicit override val activationStore: ActivationStore,
            override val entitlementProvider: EntitlementProvider,
            override val executionContext: ExecutionContext,
            override val logging: Logging)
        extends WhiskActivationsApi {
    }

    class PackagesApi(
        val apipath: String,
        val apiversion: String)(
            implicit override val entityStore: EntityStore,
            override val entitlementProvider: EntitlementProvider,
            override val activationIdFactory: ActivationIdGenerator,
            override val loadBalancer: LoadBalancerService,
            override val consulServer: String,
            override val executionContext: ExecutionContext,
            override val logging: Logging)
        extends WhiskPackagesApi with WhiskServices {
        override val whiskConfig = config
    }

    class MetasApi(
        override val apipath: String,
        override val apiversion: String)(
            implicit override val authStore: AuthStore,
            implicit val entityStore: EntityStore,
            override val activationStore: ActivationStore,
            override val entitlementProvider: EntitlementProvider,
            override val activationIdFactory: ActivationIdGenerator,
            override val loadBalancer: LoadBalancerService,
            override val consulServer: String,
            override val actorSystem: ActorSystem,
            override val executionContext: ExecutionContext,
            override val logging: Logging)
        extends WhiskMetaApi with WhiskServices {
        override val whiskConfig = config
    }

    /**
     * Handles GET /invokers URI.
     *
     * @return JSON of invoker health
     */
    val internalInvokerHealth = {
        (path("invokers") & get) {
            complete {
                loadBalancer.getInvokerHealth()
            }
        }
    }

}

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

import akka.actor.ActorSystem
import scala.concurrent.ExecutionContext
import spray.http.AllOrigins
import spray.http.HttpHeaders.`Access-Control-Allow-Origin`
import spray.http.HttpHeaders.`Access-Control-Allow-Headers`
import spray.http.StatusCodes.OK
import spray.http.StatusCodes.PermanentRedirect
import spray.http.StatusCodes.{ OK, PermanentRedirect }
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.json.JsObject
import spray.json.pimpAny
import spray.routing.Directive.pimpApply
import spray.routing.Directives
import spray.routing.Route
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.whiskVersionDate
import whisk.core.WhiskConfig.whiskVersionBuildno
import whisk.core.entitlement.{ Collection, EntitlementService }
import whisk.core.entity.{ WhiskActivationStore, WhiskAuthStore, WhiskEntityStore }
import whisk.core.entity.types.{ ActivationStore, EntityStore }
import whisk.core.loadBalancer.LoadBalancerService
import akka.event.Logging.LogLevel
import whisk.core.entity.ActivationId.ActivationIdGenerator

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
            WhiskServices.requiredProperties ++
            WhiskActionsApi.requiredProperties ++
            WhiskTriggersApi.requiredProperties ++
            WhiskRulesApi.requiredProperties ++
            WhiskActivationsApi.requiredProperties ++
            WhiskPackagesApi.requiredProperties ++
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
    verbosity: LogLevel,
    implicit val actorSystem: ActorSystem)
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
                            }
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
    protected implicit val consulServer = WhiskServices.consulServer(config)
    protected implicit val loadBalancer = WhiskServices.makeLoadBalancerComponent(config)
    protected implicit val entitlementService = WhiskServices.entitlementService(config, loadBalancer)
    protected implicit val activationId = new ActivationIdGenerator {}

    // register collections and set verbosities on datastores and backend services
    Collection.initialize(entityStore, verbosity)
    authStore.setVerbosity(verbosity)
    entityStore.setVerbosity(verbosity)
    activationStore.setVerbosity(verbosity)
    entitlementService.setVerbosity(verbosity)

    private val namespaces = new NamespacesApi(apipath, apiversion, verbosity)
    private val actions = new ActionsApi(apipath, apiversion, verbosity)
    private val triggers = new TriggersApi(apipath, apiversion, verbosity)
    private val rules = new RulesApi(apipath, apiversion, verbosity)
    private val activations = new ActivationsApi(apipath, apiversion, verbosity)
    private val packages = new PackagesApi(apipath, apiversion, verbosity)

    class NamespacesApi(
        val apipath: String,
        val apiversion: String,
        val verbosity: LogLevel)(
            implicit override val entityStore: EntityStore,
            override val entitlementService: EntitlementService,
            override val executionContext: ExecutionContext)
        extends WhiskNamespacesApi {
        setVerbosity(verbosity)
    }

    class ActionsApi(
        val apipath: String,
        val apiversion: String,
        val verbosity: LogLevel)(
            implicit override val actorSystem: ActorSystem,
            override val entityStore: EntityStore,
            override val activationStore: ActivationStore,
            override val entitlementService: EntitlementService,
            override val activationId: ActivationIdGenerator,
            override val loadBalancer: LoadBalancerService,
            override val consulServer: String,
            override val executionContext: ExecutionContext)
        extends WhiskActionsApi with WhiskServices {
        override val whiskConfig = config
        setVerbosity(verbosity)
        assert(config.actionSequenceLimit.toInt > 0)
    }

    class TriggersApi(
        val apipath: String,
        val apiversion: String,
        val verbosity: LogLevel)(
            implicit override val actorSystem: ActorSystem,
            implicit override val entityStore: EntityStore,
            override val entitlementService: EntitlementService,
            override val activationStore: ActivationStore,
            override val activationId: ActivationIdGenerator,
            override val loadBalancer: LoadBalancerService,
            override val consulServer: String,
            override val executionContext: ExecutionContext)
        extends WhiskTriggersApi with WhiskServices {
        override val whiskConfig = config
        setVerbosity(verbosity)
    }

    class RulesApi(
        val apipath: String,
        val apiversion: String,
        val verbosity: LogLevel)(
            implicit override val actorSystem: ActorSystem,
            override val entityStore: EntityStore,
            override val entitlementService: EntitlementService,
            override val activationId: ActivationIdGenerator,
            override val loadBalancer: LoadBalancerService,
            override val consulServer: String,
            override val executionContext: ExecutionContext)
        extends WhiskRulesApi with WhiskServices {
        override val whiskConfig = config
        setVerbosity(verbosity)
    }

    class ActivationsApi(
        val apipath: String,
        val apiversion: String,
        val verbosity: LogLevel)(
            implicit override val activationStore: ActivationStore,
            override val entitlementService: EntitlementService,
            override val executionContext: ExecutionContext)
        extends WhiskActivationsApi {
        setVerbosity(verbosity)
    }

    class PackagesApi(
        val apipath: String,
        val apiversion: String,
        val verbosity: LogLevel)(
            implicit override val entityStore: EntityStore,
            override val entitlementService: EntitlementService,
            override val activationId: ActivationIdGenerator,
            override val loadBalancer: LoadBalancerService,
            override val consulServer: String,
            override val executionContext: ExecutionContext)
        extends WhiskPackagesApi with WhiskServices {
        override val whiskConfig = config
        setVerbosity(verbosity)
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

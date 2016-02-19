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

import scala.annotation.implicitNotFound
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import akka.actor.ActorSystem
import spray.http.HttpRequest
import spray.http.StatusCodes.OK
import spray.http.StatusCodes.PermanentRedirect
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import spray.json.DefaultJsonProtocol.RootJsObjectFormat
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.JsObject
import spray.json.pimpAny
import spray.routing.Directive.pimpApply
import spray.routing.Directives
import spray.routing.Route

import whisk.common.TransactionId
import whisk.common.Verbosity
import whisk.common.Verbosity.Level
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.whiskVersionDate
import whisk.core.connector.LoadBalancerResponse
import whisk.core.entitlement.Collection
import whisk.core.entitlement.EntitlementService
import whisk.core.entitlement.Privilege
import whisk.core.entitlement.Resource
import whisk.core.entity.Subject
import whisk.core.entity.WhiskActivationStore
import whisk.core.entity.WhiskAuthStore
import whisk.core.entity.WhiskEntityStore
import whisk.core.entity.types.ActivationStore
import whisk.core.entity.types.AuthStore
import whisk.core.entity.types.EntityStore

abstract protected[controller] class RestAPIVersion(
    protected val apiversion: String,
    protected val build: String)
    extends Directives {

    /** Base API prefix. */
    protected val apipath = "api"

    /** Swagger end points. */
    protected val swaggeruipath = "docs"
    protected val swaggerdocpath = "api-docs"

    def prefix = pathPrefix(apipath / apiversion)
    def routes(implicit transid: TransactionId): Route
    def info = {
        JsObject(
            "openwhisk" -> "hello".toJson,
            "version" -> apiversion.toJson,
            "build" -> build.toJson)
    }
}

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

protected[controller] class RestAPIVersion_v1(
    config: WhiskConfig,
    verbosity: Verbosity.Level,
    implicit val actorSystem: ActorSystem,
    implicit val executionContext: ExecutionContext)
    extends RestAPIVersion("v1", config(whiskVersionDate))
    with Authenticate
    with AuthenticatedRoute {

    override def routes(implicit transid: TransactionId) = {
        pathPrefix(apipath / apiversion) {
            pathEndOrSingleSlash {
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
                getFromResource("resources/whiskswagger.json")
            }

        }
    }

    // initialize datastores
    protected implicit val authStore = WhiskAuthStore.datastore(config)
    protected implicit val entityStore = WhiskEntityStore.datastore(config)
    protected implicit val activationStore = WhiskActivationStore.datastore(config)

    // initialize backend services
    protected implicit val consulServer = WhiskServices.consulServer(config)
    protected implicit val entitlementService = WhiskServices.entitlementService(config)
    protected implicit val postLoadBalancerRequest = WhiskServices.postLoadBalancerRequest(config)

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
        val verbosity: Level)(
            implicit override val entityStore: EntityStore,
            override val entitlementService: EntitlementService,
            override val executionContext: ExecutionContext)
        extends WhiskNamespacesApi {
        setVerbosity(verbosity)
    }

    class ActionsApi(
        val apipath: String,
        val apiversion: String,
        val verbosity: Level)(
            implicit override val actorSystem: ActorSystem,
            override val entityStore: EntityStore,
            override val activationStore: ActivationStore,
            override val entitlementService: EntitlementService,
            override val postLoadBalancerRequest: HttpRequest => Future[LoadBalancerResponse],
            override val consulServer: String,
            override val executionContext: ExecutionContext)
        extends WhiskActionsApi with WhiskServices {
        setVerbosity(verbosity)
    }

    class TriggersApi(
        val apipath: String,
        val apiversion: String,
        val verbosity: Level)(
            implicit override val entityStore: EntityStore,
            override val entitlementService: EntitlementService,
            override val activationStore: ActivationStore,
            override val postLoadBalancerRequest: HttpRequest => Future[LoadBalancerResponse],
            override val consulServer: String,
            override val executionContext: ExecutionContext)
        extends WhiskTriggersApi with WhiskServices {
        setVerbosity(verbosity)
    }

    class RulesApi(
        val apipath: String,
        val apiversion: String,
        val verbosity: Level)(
            implicit override val actorSystem: ActorSystem,
            override val entityStore: EntityStore,
            override val entitlementService: EntitlementService,
            override val postLoadBalancerRequest: HttpRequest => Future[LoadBalancerResponse],
            override val consulServer: String,
            override val executionContext: ExecutionContext)
        extends WhiskRulesApi with WhiskServices {
        setVerbosity(verbosity)
    }

    class ActivationsApi(
        val apipath: String,
        val apiversion: String,
        val verbosity: Level)(
            implicit override val activationStore: ActivationStore,
            override val entitlementService: EntitlementService,
            override val executionContext: ExecutionContext)
        extends WhiskActivationsApi {
        setVerbosity(verbosity)
    }

    class PackagesApi(
        val apipath: String,
        val apiversion: String,
        val verbosity: Level)(
            implicit override val entityStore: EntityStore,
            override val entitlementService: EntitlementService,
            override val postLoadBalancerRequest: HttpRequest => Future[LoadBalancerResponse],
            override val consulServer: String,
            override val executionContext: ExecutionContext)
        extends WhiskPackagesApi with WhiskServices {
        setVerbosity(verbosity)
    }
}
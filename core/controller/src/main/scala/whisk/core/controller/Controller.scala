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

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

import akka.actor.Actor
import akka.actor.ActorContext
import akka.actor.ActorSystem
import akka.japi.Creator

import spray.httpx.SprayJsonSupport._
import spray.json._
import spray.json.DefaultJsonProtocol._
import spray.routing.Directive.pimpApply
import spray.routing.Route

import whisk.common.AkkaLogging
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.entitlement._
import whisk.core.entitlement.EntitlementProvider

import whisk.core.entity._
import whisk.core.entity.ExecManifest.Runtimes
import whisk.core.entity.ActivationId.ActivationIdGenerator
import whisk.core.loadBalancer.LoadBalancerService
import whisk.http.BasicHttpService
import whisk.http.BasicRasService

/**
 * The Controller is the service that provides the REST API for OpenWhisk.
 *
 * It extends the BasicRasService so it includes a ping endpoint for monitoring.
 *
 * Spray sends messages to akka Actors -- the Controller is an Actor, ready to receive messages.
 *
 * @Idioglossia uses the spray-routing DSL
 * http://spray.io/documentation/1.1.3/spray-routing/advanced-topics/understanding-dsl-structure/
 *
 * @param config A set of properties needed to run an instance of the controller service
 * @param instance if running in scale-out, a unique identifier for this instance in the group
 * @param verbosity logging verbosity
 * @param executionContext Scala runtime support for concurrent operations
 */
class Controller(
    instance: Int,
    runtimes: Runtimes,
    implicit val whiskConfig: WhiskConfig,
    implicit val logging: Logging)
    extends BasicRasService
    with Actor {

    // each akka Actor has an implicit context
    override def actorRefFactory: ActorContext = context

    /**
     * A Route in spray is technically a function taking a RequestContext as a parameter.
     *
     * @Idioglossia The ~ spray DSL operator composes two independent Routes, building a routing
     * tree structure.
     * @see http://spray.io/documentation/1.2.3/spray-routing/key-concepts/routes/#composing-routes
     */
    override def routes(implicit transid: TransactionId): Route = {
        // handleRejections wraps the inner Route with a logical error-handler for unmatched paths
        handleRejections(customRejectionHandler) {
            super.routes ~ apiv1.routes ~ apiv2.routes ~ internalInvokerHealth
        }
    }

    logging.info(this, s"starting controller instance ${instance}")

    // initialize datastores
    private implicit val actorSystem = context.system
    private implicit val executionContext = actorSystem.dispatcher
    private implicit val authStore = WhiskAuthStore.datastore(whiskConfig)
    private implicit val entityStore = WhiskEntityStore.datastore(whiskConfig)
    private implicit val activationStore = WhiskActivationStore.datastore(whiskConfig)

    // initialize backend services
    private implicit val loadBalancer = new LoadBalancerService(whiskConfig)
    private implicit val consulServer = whiskConfig.consulServer
    private implicit val entitlementProvider = new LocalEntitlementProvider(whiskConfig, loadBalancer)
    private implicit val activationIdFactory = new ActivationIdGenerator {}

    // register collections and set verbosities on datastores and backend services
    Collection.initialize(entityStore)

    /** The REST APIs. */
    private val apiv1 = new RestAPIVersion_v1
    private val apiv2 = new RestAPIVersion_v2

    /**
     * Handles GET /invokers URI.
     *
     * @return JSON of invoker health
     */
    private val internalInvokerHealth = {
        (path("invokers") & get) {
            complete {
                loadBalancer.invokerHealth.map(_.mapValues(_.asString).toJson.asJsObject)
            }
        }
    }
}

/**
 * Singleton object provides a factory to create and start an instance of the Controller service.
 */
object Controller {

    // requiredProperties is a Map whose keys define properties that must be bound to
    // a value, and whose values are default values.   A null value in the Map means there is
    // no default value specified, so it must appear in the properties file
    def requiredProperties = Map(WhiskConfig.servicePort -> 8080.toString) ++
        ExecManifest.requiredProperties ++
        RestApiCommons.requiredProperties ++
        LoadBalancerService.requiredProperties ++
        EntitlementProvider.requiredProperties

    def optionalProperties = EntitlementProvider.optionalProperties

    // akka-style factory to create a Controller object
    private class ServiceBuilder(config: WhiskConfig, instance: Int, logging: Logging) extends Creator[Controller] {
        // this method is not reached unless ExecManifest was initialized successfully
        def create = new Controller(instance, ExecManifest.runtimesManifest, config, logging)
    }

    def main(args: Array[String]): Unit = {
        implicit val actorSystem = ActorSystem("controller-actor-system")
        implicit val logger = new AkkaLogging(akka.event.Logging.getLogger(actorSystem, this))

        // extract configuration data from the environment
        val config = new WhiskConfig(requiredProperties, optionalProperties)

        // if deploying multiple instances (scale out), must pass the instance number as the
        // second argument.  (TODO .. seems fragile)
        val instance = if (args.length > 0) args(1).toInt else 0

        // initialize the runtimes manifest
        if (config.isValid && ExecManifest.initialize(config)) {
            val port = config.servicePort.toInt
            BasicHttpService.startService(actorSystem, "controller", "0.0.0.0", port, new ServiceBuilder(config, instance, logger))
        } else {
            logger.error(this, "Bad configuration, cannot start.")
            actorSystem.terminate()
            Await.result(actorSystem.whenTerminated, 30.seconds)
        }
    }
}

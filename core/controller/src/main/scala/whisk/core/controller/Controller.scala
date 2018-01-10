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

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.concurrent.Future
import scala.util.{Failure, Success}
import akka.Done
import akka.actor.ActorSystem
import akka.actor.CoordinatedShutdown
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import spray.json._

import spray.json.DefaultJsonProtocol._

import kamon.Kamon

import whisk.common.AkkaLogging
import whisk.common.Logging
import whisk.common.LoggingMarkers
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.connector.MessagingProvider
import whisk.core.database.RemoteCacheInvalidation
import whisk.core.database.CacheChangeNotification
import whisk.core.entitlement._
import whisk.core.entity._
import whisk.core.entity.ActivationId.ActivationIdGenerator
import whisk.core.entity.ExecManifest.Runtimes
import whisk.core.loadBalancer.{LoadBalancerService}
import whisk.http.BasicHttpService
import whisk.http.BasicRasService
import whisk.spi.SpiLoader
import whisk.core.containerpool.logging.LogStoreProvider

/**
 * The Controller is the service that provides the REST API for OpenWhisk.
 *
 * It extends the BasicRasService so it includes a ping endpoint for monitoring.
 *
 * Akka sends messages to akka Actors -- the Controller is an Actor, ready to receive messages.
 *
 * It is possible to deploy a hot-standby controller. Each controller needs its own instance. This instance is a
 * consecutive numbering, starting with 0.
 * The state and cache of each controller is not shared to the other controllers.
 * If the base controller crashes, the hot-standby controller will be used. After the base controller is up again,
 * it will be used again. Because of the empty cache after restart, there are no problems with inconsistency.
 * The only problem that could occur is, that the base controller is not reachable, but does not restart. After switching
 * back to the base controller, there could be an inconsistency in the cache (e.g. if a user has updated an action). This
 * inconsistency will be resolved by its own after removing the cached item, 5 minutes after it has been generated.
 *
 * Uses the Akka routing DSL: http://doc.akka.io/docs/akka-http/current/scala/http/routing-dsl/overview.html
 *
 * @param config A set of properties needed to run an instance of the controller service
 * @param instance if running in scale-out, a unique identifier for this instance in the group
 * @param verbosity logging verbosity
 * @param executionContext Scala runtime support for concurrent operations
 */
class Controller(val instance: InstanceId,
                 runtimes: Runtimes,
                 implicit val whiskConfig: WhiskConfig,
                 implicit val actorSystem: ActorSystem,
                 implicit val materializer: ActorMaterializer,
                 implicit val logging: Logging)
    extends BasicRasService {

  override val numberOfInstances = whiskConfig.controllerInstances.toInt
  override val instanceOrdinal = instance.toInt

  TransactionId.controller.mark(
    this,
    LoggingMarkers.CONTROLLER_STARTUP(instance.toInt),
    s"starting controller instance ${instance.toInt}")

  /**
   * A Route in Akka is technically a function taking a RequestContext as a parameter.
   *
   * The "~" Akka DSL operator composes two independent Routes, building a routing tree structure.
   * @see http://doc.akka.io/docs/akka-http/current/scala/http/routing-dsl/routes.html#composing-routes
   */
  override def routes(implicit transid: TransactionId): Route = {
    super.routes ~ {
      (pathEndOrSingleSlash & get) {
        complete(info)
      }
    } ~ apiV1.routes ~ swagger.swaggerRoutes ~ internalInvokerHealth
  }

  // initialize datastores
  private implicit val authStore = WhiskAuthStore.datastore(whiskConfig)
  private implicit val entityStore = WhiskEntityStore.datastore(whiskConfig)
  private implicit val activationStore = WhiskActivationStore.datastore(whiskConfig)
  private implicit val cacheChangeNotification = Some(new CacheChangeNotification {
    val remoteCacheInvalidaton = new RemoteCacheInvalidation(whiskConfig, "controller", instance)
    override def apply(k: CacheKey) = {
      remoteCacheInvalidaton.invalidateWhiskActionMetaData(k)
      remoteCacheInvalidaton.notifyOtherInstancesAboutInvalidation(k)
    }
  })

  // initialize backend services
  private implicit val loadBalancer = new LoadBalancerService(whiskConfig, instance, entityStore)
  private implicit val entitlementProvider = new LocalEntitlementProvider(whiskConfig, loadBalancer)
  private implicit val activationIdFactory = new ActivationIdGenerator {}
  private implicit val logStore = SpiLoader.get[LogStoreProvider].logStore(actorSystem)

  // register collections
  Collection.initialize(entityStore)

  /** The REST APIs. */
  implicit val controllerInstance = instance
  private val apiV1 = new RestAPIVersion(whiskConfig, "api", "v1")
  private val swagger = new SwaggerDocs(Uri.Path.Empty, "infoswagger.json")

  /**
   * Handles GET /invokers URI.
   *
   * @return JSON of invoker health
   */
  private val internalInvokerHealth = {
    implicit val executionContext = actorSystem.dispatcher

    (path("invokers") & get) {
      complete {
        loadBalancer.allInvokers.map(_.map {
          case (instance, state) => s"invoker${instance.toInt}" -> state.asString
        }.toMap.toJson.asJsObject)
      }
    }
  }

  // controller top level info
  private val info = Controller.info(whiskConfig, runtimes, List(apiV1.basepath()))
}

/**
 * Singleton object provides a factory to create and start an instance of the Controller service.
 */
object Controller {

  // requiredProperties is a Map whose keys define properties that must be bound to
  // a value, and whose values are default values.   A null value in the Map means there is
  // no default value specified, so it must appear in the properties file
  def requiredProperties =
    Map(WhiskConfig.controllerInstances -> null) ++
      ExecManifest.requiredProperties ++
      RestApiCommons.requiredProperties ++
      LoadBalancerService.requiredProperties ++
      EntitlementProvider.requiredProperties

  private def info(config: WhiskConfig, runtimes: Runtimes, apis: List[String]) =
    JsObject(
      "description" -> "OpenWhisk".toJson,
      "support" -> JsObject(
        "github" -> "https://github.com/apache/incubator-openwhisk/issues".toJson,
        "slack" -> "http://slack.openwhisk.org".toJson),
      "api_paths" -> apis.toJson,
      "limits" -> JsObject(
        "actions_per_minute" -> config.actionInvokePerMinuteLimit.toInt.toJson,
        "triggers_per_minute" -> config.triggerFirePerMinuteLimit.toInt.toJson,
        "concurrent_actions" -> config.actionInvokeConcurrentLimit.toInt.toJson),
      "runtimes" -> runtimes.toJson)

  def main(args: Array[String]): Unit = {
    Kamon.start()
    implicit val actorSystem = ActorSystem("controller-actor-system")
    implicit val logger = new AkkaLogging(akka.event.Logging.getLogger(actorSystem, this))

    // Prepare Kamon shutdown
    CoordinatedShutdown(actorSystem).addTask(CoordinatedShutdown.PhaseActorSystemTerminate, "shutdownKamon") { () =>
      logger.info(this, s"Shutting down Kamon with coordinated shutdown")
      Kamon.shutdown()
      Future.successful(Done)
    }

    // extract configuration data from the environment
    val config = new WhiskConfig(requiredProperties)
    val port = config.servicePort.toInt

    // if deploying multiple instances (scale out), must pass the instance number as the
    require(args.length >= 1, "controller instance required")
    val instance = args(0).toInt

    def abort(message: String) = {
      logger.error(this, message)
      actorSystem.terminate()
      Await.result(actorSystem.whenTerminated, 30.seconds)
      sys.exit(1)
    }

    if (!config.isValid) {
      abort("Bad configuration, cannot start.")
    }

    val msgProvider = SpiLoader.get[MessagingProvider]
    if (!msgProvider.ensureTopic(topic = "completed" + instance, topicConfig = "completed")) {
      abort(s"failure during msgProvider.ensureTopic for topic completed$instance")
    }
    if (!msgProvider.ensureTopic(topic = "health", topicConfig = "health")) {
      abort(s"failure during msgProvider.ensureTopic for topic health")
    }
    if (!msgProvider.ensureTopic(topic = "cacheInvalidation", topicConfig = "cache-invalidation")) {
      abort(s"failure during msgProvider.ensureTopic for topic cacheInvalidation")
    }

    ExecManifest.initialize(config) match {
      case Success(_) =>
        val controller = new Controller(
          InstanceId(instance),
          ExecManifest.runtimesManifest,
          config,
          actorSystem,
          ActorMaterializer.create(actorSystem),
          logger)
        BasicHttpService.startService(controller.route, port)(actorSystem, controller.materializer)

      case Failure(t) =>
        abort(s"Invalid runtimes manifest: $t")
    }
  }
}

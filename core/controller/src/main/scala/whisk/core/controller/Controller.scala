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
import whisk.core.database.{ActivationStoreProvider, CacheChangeNotification, RemoteCacheInvalidation}
import whisk.core.entitlement._
import whisk.core.entity._
import whisk.core.entity.ActivationId.ActivationIdGenerator
import whisk.core.entity.ExecManifest.Runtimes
import whisk.core.loadBalancer.{InvokerState, LoadBalancerProvider}
import whisk.http.BasicHttpService
import whisk.http.BasicRasService
import whisk.spi.SpiLoader
import whisk.core.containerpool.logging.LogStoreProvider
import akka.event.Logging.InfoLevel
import pureconfig.loadConfigOrThrow

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
class Controller(val instance: ControllerInstanceId,
                 runtimes: Runtimes,
                 implicit val whiskConfig: WhiskConfig,
                 implicit val actorSystem: ActorSystem,
                 implicit val materializer: ActorMaterializer,
                 implicit val logging: Logging)
    extends BasicRasService {

  TransactionId.controller.mark(
    this,
    LoggingMarkers.CONTROLLER_STARTUP(instance.asString),
    s"starting controller instance ${instance.asString}",
    logLevel = InfoLevel)

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
  private implicit val authStore = WhiskAuthStore.datastore()
  private implicit val entityStore = WhiskEntityStore.datastore()
  private implicit val cacheChangeNotification = Some(new CacheChangeNotification {
    val remoteCacheInvalidaton = new RemoteCacheInvalidation(whiskConfig, "controller", instance)
    override def apply(k: CacheKey) = {
      remoteCacheInvalidaton.invalidateWhiskActionMetaData(k)
      remoteCacheInvalidaton.notifyOtherInstancesAboutInvalidation(k)
    }
  })

  // initialize backend services
  private implicit val loadBalancer =
    SpiLoader.get[LoadBalancerProvider].instance(whiskConfig, instance)
  logging.info(this, s"loadbalancer initialized: ${loadBalancer.getClass.getSimpleName}")(TransactionId.controller)

  private implicit val entitlementProvider =
    SpiLoader.get[EntitlementSpiProvider].instance(whiskConfig, loadBalancer, instance)
  private implicit val activationIdFactory = new ActivationIdGenerator {}
  private implicit val logStore = SpiLoader.get[LogStoreProvider].instance(actorSystem)
  private implicit val activationStore =
    SpiLoader.get[ActivationStoreProvider].instance(actorSystem, materializer, logging)

  // register collections
  Collection.initialize(entityStore)

  /** The REST APIs. */
  implicit val controllerInstance = instance
  private val apiV1 = new RestAPIVersion(whiskConfig, "api", "v1")
  private val swagger = new SwaggerDocs(Uri.Path.Empty, "infoswagger.json")

  /**
   * Handles GET /invokers
   *             /invokers/healthy/count
   *
   * @return JSON with details of invoker health or count of healthy invokers respectively.
   */
  private val internalInvokerHealth = {
    implicit val executionContext = actorSystem.dispatcher
    (pathPrefix("invokers") & get) {
      pathEndOrSingleSlash {
        complete {
          loadBalancer
            .invokerHealth()
            .map(_.map(i => i.id.toString -> i.status.asString).toMap.toJson.asJsObject)
        }
      } ~ path("healthy" / "count") {
        complete {
          loadBalancer
            .invokerHealth()
            .map(_.count(_.status == InvokerState.Healthy).toJson)
        }
      }
    }
  }

  // controller top level info
  private val info = Controller.info(
    whiskConfig,
    TimeLimit.config,
    MemoryLimit.config,
    LogLimit.config,
    runtimes,
    List(apiV1.basepath()))
}

/**
 * Singleton object provides a factory to create and start an instance of the Controller service.
 */
object Controller {

  protected val protocol = loadConfigOrThrow[String]("whisk.controller.protocol")

  // requiredProperties is a Map whose keys define properties that must be bound to
  // a value, and whose values are default values.   A null value in the Map means there is
  // no default value specified, so it must appear in the properties file
  def requiredProperties =
    Map(WhiskConfig.controllerInstances -> null) ++
      ExecManifest.requiredProperties ++
      RestApiCommons.requiredProperties ++
      SpiLoader.get[LoadBalancerProvider].requiredProperties ++
      EntitlementProvider.requiredProperties

  private def info(config: WhiskConfig,
                   timeLimit: TimeLimitConfig,
                   memLimit: MemoryLimitConfig,
                   logLimit: MemoryLimitConfig,
                   runtimes: Runtimes,
                   apis: List[String]) =
    JsObject(
      "description" -> "OpenWhisk".toJson,
      "support" -> JsObject(
        "github" -> "https://github.com/apache/incubator-openwhisk/issues".toJson,
        "slack" -> "http://slack.openwhisk.org".toJson),
      "api_paths" -> apis.toJson,
      "limits" -> JsObject(
        "actions_per_minute" -> config.actionInvokePerMinuteLimit.toInt.toJson,
        "triggers_per_minute" -> config.triggerFirePerMinuteLimit.toInt.toJson,
        "concurrent_actions" -> config.actionInvokeConcurrentLimit.toInt.toJson,
        "sequence_length" -> config.actionSequenceLimit.toInt.toJson,
        "min_action_duration" -> timeLimit.min.toMillis.toJson,
        "max_action_duration" -> timeLimit.max.toMillis.toJson,
        "min_action_memory" -> memLimit.min.toBytes.toJson,
        "max_action_memory" -> memLimit.max.toBytes.toJson,
        "min_action_logs" -> logLimit.min.toBytes.toJson,
        "max_action_logs" -> logLimit.max.toBytes.toJson),
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
    val instance = ControllerInstanceId(args(0))

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

    Map(
      "completed" + instance.asString -> "completed",
      "health" -> "health",
      "cacheInvalidation" -> "cache-invalidation",
      "events" -> "events").foreach {
      case (topic, topicConfigurationKey) =>
        if (msgProvider.ensureTopic(config, topic, topicConfigurationKey).isFailure) {
          abort(s"failure during msgProvider.ensureTopic for topic $topic")
        }
    }

    ExecManifest.initialize(config) match {
      case Success(_) =>
        val controller = new Controller(
          instance,
          ExecManifest.runtimesManifest,
          config,
          actorSystem,
          ActorMaterializer.create(actorSystem),
          logger)
        if (Controller.protocol == "https")
          BasicHttpService.startHttpsService(controller.route, port, config)(actorSystem, controller.materializer)
        else
          BasicHttpService.startHttpService(controller.route, port)(actorSystem, controller.materializer)

      case Failure(t) =>
        abort(s"Invalid runtimes manifest: $t")
    }
  }
}

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

package org.apache.openwhisk.core.scheduler

import akka.Done
import akka.actor.{ActorRef, ActorRefFactory, ActorSelection, ActorSystem, CoordinatedShutdown, Props}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.util.Timeout
import akka.pattern.ask
import com.typesafe.config.ConfigValueFactory
import kamon.Kamon
import org.apache.openwhisk.common.Https.HttpsConfig
import org.apache.openwhisk.common._
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import org.apache.openwhisk.core.WhiskConfig.{servicePort, _}
import org.apache.openwhisk.core.ack.{MessagingActiveAck, UserEventSender}
import org.apache.openwhisk.core.connector._
import org.apache.openwhisk.core.database.{ActivationStoreProvider, NoDocumentException, UserContext}
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.etcd.EtcdKV.{QueueKeys, SchedulerKeys}
import org.apache.openwhisk.core.etcd.EtcdType.ByteStringToString
import org.apache.openwhisk.core.etcd.{EtcdClient, EtcdConfig}
import org.apache.openwhisk.core.scheduler.container.{ContainerManager, CreationJobManager}
import org.apache.openwhisk.core.scheduler.grpc.ActivationServiceImpl
import org.apache.openwhisk.core.scheduler.queue.{
  DurationCheckerProvider,
  MemoryQueue,
  QueueManager,
  QueueSize,
  SchedulingDecisionMaker
}
import org.apache.openwhisk.core.service.{DataManagementService, EtcdWorker, LeaseKeepAliveService, WatcherService}
import org.apache.openwhisk.grpc.ActivationServiceHandler
import org.apache.openwhisk.http.BasicHttpService
import org.apache.openwhisk.spi.SpiLoader
import org.apache.openwhisk.utils.ExecutionContextFactory
import pureconfig.loadConfigOrThrow
import spray.json.{DefaultJsonProtocol, _}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}
import pureconfig.generic.auto._

import scala.collection.JavaConverters

class Scheduler(schedulerId: SchedulerInstanceId, schedulerEndpoints: SchedulerEndpoints)(implicit config: WhiskConfig,
                                                                                          actorSystem: ActorSystem,
                                                                                          logging: Logging)
    extends SchedulerCore {
  implicit val ec = actorSystem.dispatcher
  private val authStore = WhiskAuthStore.datastore()

  val msgProvider = SpiLoader.get[MessagingProvider]
  val producer = msgProvider.getProducer(config, Some(ActivationEntityLimit.MAX_ACTIVATION_LIMIT))

  val maxPeek = loadConfigOrThrow[Int](ConfigKeys.schedulerMaxPeek)
  val etcdClient = EtcdClient(loadConfigOrThrow[EtcdConfig](ConfigKeys.etcd).hosts)
  val watcherService: ActorRef = actorSystem.actorOf(WatcherService.props(etcdClient))
  val leaseService =
    actorSystem.actorOf(LeaseKeepAliveService.props(etcdClient, schedulerId, watcherService))

  implicit val entityStore = WhiskEntityStore.datastore()
  private val activationStore =
    SpiLoader.get[ActivationStoreProvider].instance(actorSystem, logging)

  private val ack = {
    val sender = if (UserEvents.enabled) Some(new UserEventSender(producer)) else None
    new MessagingActiveAck(producer, schedulerId, sender)
  }

  /** Stores an activation in the database. */
  private val store = (tid: TransactionId, activation: WhiskActivation, context: UserContext) => {
    implicit val transid: TransactionId = tid
    activationStore.store(activation, context)(tid, notifier = None).andThen {
      case Success(doc) => logging.info(this, s"save ${doc} successfully")
      case Failure(t)   => logging.error(this, s"failed to save activation $activation, error: ${t.getMessage}")
    }
  }
  val durationCheckerProvider = SpiLoader.get[DurationCheckerProvider]
  val durationChecker = durationCheckerProvider.instance(actorSystem, logging)

  override def getState: Future[(List[(SchedulerInstanceId, Int)], Int)] = {
    logging.info(this, s"getting the queue states")
    etcdClient
      .getPrefix(s"${QueueKeys.inProgressPrefix}/${QueueKeys.queuePrefix}")
      .map(res => {
        JavaConverters
          .asScalaIteratorConverter(res.getKvsList.iterator())
          .asScala
          .map(kv => ByteStringToString(kv.getValue))
          .count(_ == schedulerId.asString)
      })
      .flatMap { creationCount =>
        etcdClient
          .get(SchedulerKeys.scheduler(schedulerId))
          .map(res => {
            JavaConverters
              .asScalaIteratorConverter(res.getKvsList.iterator())
              .asScala
              .map { kv =>
                SchedulerStates.parse(kv.getValue).getOrElse(SchedulerStates(schedulerId, -1, schedulerEndpoints))
              }
              .map { schedulerState =>
                (schedulerState.sid, schedulerState.queueSize)
              }
              .toList
          })
          .map { list =>
            (list, creationCount)
          }
      }
  }

  override def getQueueSize: Future[Int] = {
    queueManager.ask(QueueSize)(Timeout(5.seconds)).mapTo[Int]
  }

  override def getQueueStatusData: Future[List[StatusData]] = {
    queueManager.ask(StatusQuery)(Timeout(5.seconds)).mapTo[Future[List[StatusData]]].flatten
  }

  override def disable(): Unit = {
    logging.info(this, s"Gracefully shutting down the scheduler")
    containerManager ! GracefulShutdown
    queueManager ! GracefulShutdown
  }

  private def getUserLimit(invocationNamespace: String): Future[Int] = {
    Identity
      .get(authStore, EntityName(invocationNamespace))(trasnid)
      .map { identity =>
        val limit = identity.limits.concurrentInvocations.getOrElse(config.actionInvokeConcurrentLimit.toInt)
        logging.debug(this, s"limit for ${invocationNamespace}: ${limit}")(trasnid)
        limit
      }
      .andThen {
        case Failure(_: NoDocumentException) =>
          logging.warn(this, s"namespace does not exist: $invocationNamespace")(trasnid)
        case Failure(_: IllegalStateException) =>
          logging.warn(this, s"namespace is not unique: $invocationNamespace")(trasnid)
      }
  }

  private val etcdWorkerFactory = (f: ActorRefFactory) => f.actorOf(EtcdWorker.props(etcdClient, leaseService))

  /**
   * This component is in charge of storing data to ETCD.
   * Even if any error happens we can assume the data will be eventually available in the ETCD by this component.
   */
  val dataManagementService: ActorRef =
    actorSystem.actorOf(DataManagementService.props(watcherService, etcdWorkerFactory))

  val feedFactory = (f: ActorRefFactory,
                     description: String,
                     topic: String,
                     maxActiveAcksPerPoll: Int,
                     processAck: Array[Byte] => Future[Unit]) => {
    val consumer = msgProvider.getConsumer(config, topic, topic, maxActiveAcksPerPoll)
    f.actorOf(Props(new MessageFeed(description, logging, consumer, maxActiveAcksPerPoll, 1.second, processAck)))
  }

  val creationJobManagerFactory: ActorRefFactory => ActorRef =
    factory => {
      factory.actorOf(CreationJobManager.props(feedFactory, schedulerId, dataManagementService))
    }

  /**
   * This component is responsible for creating containers for a given action.
   * It relies on the creationJobManager to manage the container creation job.
   */
  val containerManager: ActorRef =
    actorSystem.actorOf(
      ContainerManager.props(creationJobManagerFactory, msgProvider, schedulerId, etcdClient, config, watcherService))

  /**
   * This is a factory to create memory queues.
   * In the new architecture, each action is given its own dedicated queue.
   */
  val memoryQueueFactory
    : (ActorRefFactory, String, FullyQualifiedEntityName, DocRevision, WhiskActionMetaData) => ActorRef =
    (factory, invocationNamespace, fqn, revision, actionMetaData) => {
      // Todo: Change this to SPI
      val decisionMaker = factory.actorOf(SchedulingDecisionMaker.props(invocationNamespace, fqn))

      factory.actorOf(
        MemoryQueue.props(
          etcdClient,
          durationChecker,
          fqn,
          producer,
          config,
          invocationNamespace,
          revision,
          schedulerEndpoints,
          actionMetaData,
          dataManagementService,
          watcherService,
          containerManager,
          decisionMaker,
          schedulerId: SchedulerInstanceId,
          ack,
          store: (TransactionId, WhiskActivation, UserContext) => Future[Any],
          getUserLimit: String => Future[Int]))
    }

  val topic = s"${Scheduler.topicPrefix}scheduler${schedulerId.asString}"
  val schedulerConsumer =
    msgProvider.getConsumer(config, topic, topic, maxPeek, maxPollInterval = TimeLimit.MAX_DURATION + 1.minute)

  implicit val trasnid = TransactionId.containerCreation

  /**
   * This is one of the major components which take charge of managing queues and coordinating requests among the scheduler, controllers, and invokers.
   */
  val queueManager = actorSystem.actorOf(
    QueueManager.props(
      entityStore,
      WhiskActionMetaData.get,
      etcdClient,
      schedulerEndpoints,
      schedulerId,
      dataManagementService,
      watcherService,
      ack,
      store: (TransactionId, WhiskActivation, UserContext) => Future[Any],
      memoryQueueFactory,
      schedulerConsumer),
    QueueManager.actorName)

  val serviceHandlers: HttpRequest => Future[HttpResponse] = ActivationServiceHandler.apply(ActivationServiceImpl())
}

case class CmdLineArgs(uniqueName: Option[String] = None, id: Option[Int] = None, displayedName: Option[String] = None)

trait SchedulerCore {
  def getState: Future[(List[(SchedulerInstanceId, Int)], Int)]

  def getQueueSize: Future[Int]

  def getQueueStatusData: Future[List[StatusData]]

  def disable(): Unit
}

object Scheduler {

  protected val protocol = loadConfigOrThrow[String]("whisk.scheduler.protocol")

  val topicPrefix = loadConfigOrThrow[String](ConfigKeys.kafkaTopicsPrefix)

  /**
   * The scheduler has two ports, one for akka-remote and the other for akka-grpc.
   */
  def requiredProperties =
    Map(
      servicePort -> 8080.toString,
      schedulerHost -> null,
      schedulerAkkaPort -> null,
      schedulerRpcPort -> null,
      WhiskConfig.actionInvokePerMinuteLimit -> null,
      WhiskConfig.actionInvokeConcurrentLimit -> null,
      WhiskConfig.triggerFirePerMinuteLimit -> null) ++
      kafkaHosts ++
      zookeeperHosts ++
      wskApiHost ++
      ExecManifest.requiredProperties

  def initKamon(instance: SchedulerInstanceId): Unit = {
    // Replace the hostname of the scheduler to the assigned id of the scheduler.
    val newKamonConfig = Kamon.config
      .withValue("kamon.environment.host", ConfigValueFactory.fromAnyRef(s"scheduler${instance.asString}"))
    Kamon.init(newKamonConfig)
  }

  def main(args: Array[String]): Unit = {
    implicit val ec = ExecutionContextFactory.makeCachedThreadPoolExecutionContext()
    implicit val actorSystem: ActorSystem =
      ActorSystem(name = "scheduler-actor-system", defaultExecutionContext = Some(ec))

    implicit val logger = new AkkaLogging(akka.event.Logging.getLogger(actorSystem, this))

    // Prepare Kamon shutdown
    CoordinatedShutdown(actorSystem).addTask(CoordinatedShutdown.PhaseActorSystemTerminate, "shutdownKamon") { () =>
      logger.info(this, s"Shutting down Kamon with coordinated shutdown")
      Kamon.stopModules().map(_ => Done)
    }

    def abort(message: String) = {
      logger.error(this, message)
      actorSystem.terminate()
      Await.result(actorSystem.whenTerminated, 30.seconds)
      sys.exit(1)
    }

    // extract configuration data from the environment
    implicit val config = new WhiskConfig(requiredProperties)
    if (!config.isValid) {
      abort("Bad configuration, cannot start.")
    }

    val port = config.servicePort.toInt
    val host = config.schedulerHost
    val rpcPort = config.schedulerRpcPort.toInt
    val akkaPort = config.schedulerAkkaPort.toInt

    // if deploying multiple instances (scale out), must pass the instance number as they need to be uniquely identified.
    require(args.length >= 1, "scheduler instance required")
    val instanceId = SchedulerInstanceId(args(0))

    initKamon(instanceId)

    val msgProvider = SpiLoader.get[MessagingProvider]

    Seq(
      (topicPrefix + "scheduler" + instanceId.asString, "actions", Some(ActivationEntityLimit.MAX_ACTIVATION_LIMIT)),
      (
        topicPrefix + "creationAck" + instanceId.asString,
        "creationAck",
        Some(ActivationEntityLimit.MAX_ACTIVATION_LIMIT)))
      .foreach {
        case (topic, topicConfigurationKey, maxMessageBytes) =>
          if (msgProvider.ensureTopic(config, topic, topicConfigurationKey, maxMessageBytes).isFailure) {
            abort(s"failure during msgProvider.ensureTopic for topic $topic")
          }
      }

    ExecManifest.initialize(config) match {
      case Success(_) =>
        val schedulerEndpoints = SchedulerEndpoints(host, rpcPort, akkaPort)
        // Create scheduler
        val scheduler = new Scheduler(instanceId, schedulerEndpoints)

        // TODO: Add Akka-grpc handler
        val httpsConfig =
          if (Scheduler.protocol == "https") Some(loadConfigOrThrow[HttpsConfig]("whisk.controller.https")) else None

        BasicHttpService.startHttpService(FPCSchedulerServer.instance(scheduler).route, port, httpsConfig)(actorSystem)

      case Failure(t) =>
        abort(s"Invalid runtimes manifest: $t")
    }
  }
}
case class SchedulerEndpoints(host: String, rpcPort: Int, akkaPort: Int) {
  require(rpcPort != 0 || akkaPort != 0)
  def asRpcEndpoint: String = s"$host:$rpcPort"
  def asAkkaEndpoint: String = s"$host:$akkaPort"

  def getRemoteRef(name: String)(implicit context: ActorRefFactory): ActorSelection = {
    implicit val ec = context.dispatcher

    val path = s"akka://scheduler-actor-system@${asAkkaEndpoint}/user/${name}"
    context.actorSelection(path)
  }

  def serialize = SchedulerEndpoints.serdes.write(this).compactPrint
}

object SchedulerEndpoints extends DefaultJsonProtocol {
  implicit val serdes = jsonFormat(SchedulerEndpoints.apply, "host", "rpcPort", "akkaPort")
  def parse(endpoints: String) = Try(serdes.read(endpoints.parseJson))
}

case class SchedulerStates(sid: SchedulerInstanceId, queueSize: Int, endpoints: SchedulerEndpoints) {
  private implicit val askTimeout = Timeout(5 seconds)

  def getRemoteRef(name: String)(implicit context: ActorRefFactory): ActorSelection = {
    implicit val ec = context.dispatcher

    val path = s"akka//scheduler-actor-system@${endpoints.asAkkaEndpoint}/user/${name}"
    context.actorSelection(path)
  }

  def getSchedulerId(): SchedulerInstanceId = sid

  def serialize = SchedulerStates.serdes.write(this).compactPrint
}

object SchedulerStates extends DefaultJsonProtocol {
  private implicit val endpointsSerde = SchedulerEndpoints.serdes
  implicit val serdes = jsonFormat(SchedulerStates.apply, "sid", "queueSize", "endpoints")

  def parse(states: String) = Try(serdes.read(states.parseJson))
}

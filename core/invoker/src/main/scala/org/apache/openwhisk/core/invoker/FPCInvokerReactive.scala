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

package org.apache.openwhisk.core.invoker

import org.apache.pekko.Done
import org.apache.pekko.actor.{ActorRef, ActorRefFactory, ActorSystem, CoordinatedShutdown, Props}
import org.apache.pekko.grpc.GrpcClientSettings
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout
import com.ibm.etcd.api.Event.EventType
import com.ibm.etcd.client.kv.KvClient.Watch
import com.ibm.etcd.client.kv.WatchUpdate
import org.apache.openwhisk.common._
import org.apache.openwhisk.core.ack.{ActiveAck, HealthActionAck, MessagingActiveAck, UserEventSender}
import org.apache.openwhisk.core.connector._
import org.apache.openwhisk.core.containerpool._
import org.apache.openwhisk.core.containerpool.logging.LogStoreProvider
import org.apache.openwhisk.core.containerpool.v2._
import org.apache.openwhisk.core.database._
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.etcd.EtcdKV.ContainerKeys.containerPrefix
import org.apache.openwhisk.core.etcd.EtcdKV.QueueKeys.queue
import org.apache.openwhisk.core.etcd.EtcdKV.{ContainerKeys, SchedulerKeys}
import org.apache.openwhisk.core.etcd.EtcdType._
import org.apache.openwhisk.core.etcd.{EtcdClient, EtcdConfig, EtcdWorker}
import org.apache.openwhisk.core.invoker.Invoker.InvokerEnabled
import org.apache.openwhisk.core.scheduler.{SchedulerEndpoints, SchedulerStates}
import org.apache.openwhisk.core.service.{DataManagementService, LeaseKeepAliveService, WatcherService}
import org.apache.openwhisk.core.{ConfigKeys, WarmUp, WhiskConfig}
import org.apache.openwhisk.grpc.{ActivationServiceClient, FetchRequest}
import org.apache.openwhisk.spi.SpiLoader
import pureconfig._
import pureconfig.generic.auto._

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}

case class GrpcServiceConfig(tls: Boolean)

object FPCInvokerReactive extends InvokerProvider {

  override def instance(
    config: WhiskConfig,
    instance: InvokerInstanceId,
    producer: MessageProducer,
    poolConfig: ContainerPoolConfig,
    limitsConfig: IntraConcurrencyLimitConfig)(implicit actorSystem: ActorSystem, logging: Logging): InvokerCore =
    new FPCInvokerReactive(config, instance, producer, poolConfig, limitsConfig)
}

class FPCInvokerReactive(config: WhiskConfig,
                         instance: InvokerInstanceId,
                         producer: MessageProducer,
                         poolConfig: ContainerPoolConfig =
                           loadConfigOrThrow[ContainerPoolConfig](ConfigKeys.containerPool),
                         limitsConfig: IntraConcurrencyLimitConfig = loadConfigOrThrow[IntraConcurrencyLimitConfig](
                           ConfigKeys.concurrencyLimit))(implicit actorSystem: ActorSystem, logging: Logging)
    extends InvokerCore {

  implicit val ec: ExecutionContext = actorSystem.dispatcher
  implicit val exe: ExecutionContextExecutor = actorSystem.dispatcher
  implicit val cfg: WhiskConfig = config

  private val logsProvider = SpiLoader.get[LogStoreProvider].instance(actorSystem)
  logging.info(this, s"LogStoreProvider: ${logsProvider.getClass}")

  private val etcdClient = EtcdClient(loadConfigOrThrow[EtcdConfig](ConfigKeys.etcd))

  private val grpcConfig = loadConfigOrThrow[GrpcServiceConfig](ConfigKeys.schedulerGrpcService)

  val watcherService: ActorRef = actorSystem.actorOf(WatcherService.props(etcdClient))

  private val leaseService =
    actorSystem.actorOf(LeaseKeepAliveService.props(etcdClient, instance, watcherService))

  private val etcdWorkerFactory =
    (f: ActorRefFactory) => f.actorOf(EtcdWorker.props(etcdClient, leaseService))

  val dataManagementService: ActorRef =
    actorSystem.actorOf(DataManagementService.props(watcherService, etcdWorkerFactory))

  private val warmedSchedulers = TrieMap[String, String]()
  private var warmUpWatcher: Option[Watch] = None

  /**
   * Factory used by the ContainerProxy to physically create a new container.
   *
   * Create and initialize the container factory before kicking off any other
   * task or actor because further operation does not make sense if something
   * goes wrong here. Initialization will throw an exception upon failure.
   */
  private val containerFactory =
    SpiLoader
      .get[ContainerFactoryProvider]
      .instance(
        actorSystem,
        logging,
        config,
        instance,
        Map(
          "--cap-drop" -> Set("NET_RAW", "NET_ADMIN"),
          "--ulimit" -> Set("nofile=1024:1024"),
          "--pids-limit" -> Set("1024")) ++ logsProvider.containerParameters)
  containerFactory.init()

  CoordinatedShutdown(actorSystem)
    .addTask(CoordinatedShutdown.PhaseBeforeActorSystemTerminate, "cleanup runtime containers") { () =>
      containerFactory.cleanup()
      Future.successful(Done)
    }

  /** Initialize needed databases */
  private val entityStore = WhiskEntityStore.datastore()
  private val activationStore =
    SpiLoader.get[ActivationStoreProvider].instance(actorSystem, logging)

  private val authStore = WhiskAuthStore.datastore()

  private val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)

  Scheduler.scheduleWaitAtMost(loadConfigOrThrow[NamespaceBlacklistConfig](ConfigKeys.blacklist).pollInterval) { () =>
    logging.debug(this, "running background job to update blacklist")
    namespaceBlacklist.refreshBlacklist()(ec, TransactionId.invoker).andThen {
      case Success(set) => logging.info(this, s"updated blacklist to ${set.size} entries")
      case Failure(t)   => logging.error(this, s"error on updating the blacklist: ${t.getMessage}")
    }
  }

  val containerProxyTimeoutConfig = loadConfigOrThrow[ContainerProxyTimeoutConfig](ConfigKeys.containerProxyTimeouts)

  private def getWarmedContainerLimit(invocationNamespace: String): Future[(Int, FiniteDuration)] = {
    implicit val trasnid = TransactionId.unknown
    Identity
      .get(authStore, EntityName(invocationNamespace))(trasnid)
      .map { identity =>
        val warmedContainerKeepingCount = identity.limits.warmedContainerKeepingCount.getOrElse(0)
        val warmedContainerKeepingTimeout = Try {
          identity.limits.warmedContainerKeepingTimeout.map(Duration(_).toSeconds.seconds).get
        }.getOrElse(containerProxyTimeoutConfig.keepingDuration)
        (warmedContainerKeepingCount, warmedContainerKeepingTimeout)
      }
      .andThen {
        case Failure(_: NoDocumentException) =>
          logging.warn(this, s"namespace does not exist: $invocationNamespace")(trasnid)
        case Failure(_: IllegalStateException) =>
          logging.warn(this, s"namespace is not unique: $invocationNamespace")(trasnid)
      }
  }

  private val ack = {
    val sender = if (UserEvents.enabled) Some(new UserEventSender(producer)) else None
    new MessagingActiveAck(producer, instance, sender)
  }

  // we don't need to store health action results in normal case
  private val healthActionAck: ActiveAck = new HealthActionAck(producer)

  private val collectLogs = new LogStoreCollector(logsProvider)

  /** Stores an activation in the database. */
  private val store = (tid: TransactionId, activation: WhiskActivation, isBlocking: Boolean, context: UserContext) => {
    implicit val transid: TransactionId = tid
    activationStore.storeAfterCheck(activation, isBlocking, None, None, context)(tid, notifier = None, logging)
  }

  private def healthActivationClientFactory(f: ActorRefFactory,
                                            invocationNamespace: String,
                                            fqn: FullyQualifiedEntityName,
                                            rev: DocRevision,
                                            schedulerHost: String,
                                            rpcPort: Int,
                                            containerId: ContainerId): ActorRef =
    f.actorOf(Props(HealthActivationServiceClient()))

  private def healthContainerProxyFactory(f: ActorRefFactory, healthManger: ActorRef): ActorRef = {
    implicit val transId = TransactionId.invokerNanny
    f.actorOf(
      FunctionPullingContainerProxy
        .props(
          containerFactory.createContainer,
          entityStore,
          namespaceBlacklist,
          WhiskAction.get,
          dataManagementService,
          healthActivationClientFactory,
          healthActionAck,
          store,
          collectLogs,
          getLiveContainerCount,
          getWarmedContainerLimit,
          instance,
          healthManger,
          poolConfig,
          containerProxyTimeoutConfig))
  }

  private val invokerHealthManager =
    actorSystem.actorOf(
      InvokerHealthManager.props(instance, healthContainerProxyFactory, dataManagementService, entityStore))

  invokerHealthManager ! Enable

  private def activationClientFactory(etcd: EtcdClient)(
    invocationNamespace: String,
    fqn: FullyQualifiedEntityName,
    schedulerHost: String,
    rpcPort: Int,
    tryOtherScheduler: Boolean = false): Future[ActivationServiceClient] = {

    if (!tryOtherScheduler) {
      val setting =
        GrpcClientSettings
          .connectToServiceAt(schedulerHost, rpcPort)
          .withTls(grpcConfig.tls)
      Future {
        ActivationServiceClient(setting)
      }.andThen {
        case Failure(t) =>
          logging.error(
            this,
            s"unable to create activation client for action ${fqn}: ${t} on original scheduler: ${schedulerHost}:${rpcPort}")
      }
    } else {
      val leaderKey = queue(invocationNamespace, fqn, leader = true)
      etcd
        .get(leaderKey)
        .flatMap { res =>
          require(!res.getKvsList.isEmpty)

          val endpoint: String = res.getKvsList.get(0).getValue
          Future(SchedulerEndpoints.parse(endpoint))
            .flatMap(Future.fromTry)
            .map { schedulerEndpoint =>
              val setting =
                GrpcClientSettings
                  .connectToServiceAt(schedulerEndpoint.host, schedulerEndpoint.rpcPort)
                  .withTls(grpcConfig.tls)

              ActivationServiceClient(setting)
            }
            .andThen {
              case Failure(t) =>
                logging.error(this, s"unable to create activation client for action ${fqn}: ${t}")
            }
        }
    }

  }

  private def sendAckToScheduler(schedulerInstanceId: SchedulerInstanceId,
                                 creationAckMessage: ContainerCreationAckMessage): Future[ResultMetadata] = {
    val topic = s"${Invoker.topicPrefix}creationAck${schedulerInstanceId.asString}"
    val reschedulable =
      creationAckMessage.error.map(ContainerCreationError.whiskErrors.contains(_)).getOrElse(false)
    if (reschedulable) {
      MetricEmitter.emitCounterMetric(
        LoggingMarkers.INVOKER_CONTAINER_CREATE(creationAckMessage.action.toString, "reschedule"))
    } else if (creationAckMessage.error.nonEmpty) {
      MetricEmitter.emitCounterMetric(
        LoggingMarkers.INVOKER_CONTAINER_CREATE(creationAckMessage.action.toString, "failed"))
    }

    producer.send(topic, creationAckMessage).andThen {
      case Success(_) =>
        logging.info(
          this,
          s"Posted ${if (reschedulable) "rescheduling"
          else if (creationAckMessage.error.nonEmpty) "failed"
          else "success"} ack of container creation ${creationAckMessage.creationId} for ${creationAckMessage.invocationNamespace}/${creationAckMessage.action}")
      case Failure(t) =>
        logging.error(
          this,
          s"failed to send container creation ack message(${creationAckMessage.creationId}) for ${creationAckMessage.invocationNamespace}/${creationAckMessage.action} to scheduler: ${t.getMessage}")
    }
  }

  /** Creates a ContainerProxy Actor when being called. */
  private val childFactory = (f: ActorRefFactory) => {
    implicit val transId = TransactionId.invokerNanny
    f.actorOf(
      FunctionPullingContainerProxy
        .props(
          containerFactory.createContainer,
          entityStore,
          namespaceBlacklist,
          WhiskAction.get,
          dataManagementService,
          clientProxyFactory,
          ack,
          store,
          collectLogs,
          getLiveContainerCount,
          getWarmedContainerLimit,
          instance,
          invokerHealthManager,
          poolConfig,
          containerProxyTimeoutConfig))
  }

  /** Creates a ActivationClientProxy Actor when being called. */
  private def clientProxyFactory(f: ActorRefFactory,
                                 invocationNamespace: String,
                                 fqn: FullyQualifiedEntityName,
                                 rev: DocRevision,
                                 schedulerHost: String,
                                 rpcPort: Int,
                                 containerId: ContainerId): ActorRef = {
    implicit val transId = TransactionId.invokerNanny
    f.actorOf(
      ActivationClientProxy
        .props(invocationNamespace, fqn, rev, schedulerHost, rpcPort, containerId, activationClientFactory(etcdClient)))
  }

  val prewarmingConfigs: List[PrewarmingConfig] = {
    ExecManifest.runtimesManifest.stemcells.flatMap {
      case (mf, cells) =>
        cells.map { cell =>
          PrewarmingConfig(cell.initialCount, new CodeExecAsString(mf, "", None), cell.memory)
        }
    }.toList
  }

  private val pool =
    actorSystem.actorOf(
      ContainerPoolV2
        .props(childFactory, invokerHealthManager, poolConfig, instance, prewarmingConfigs, sendAckToScheduler))

  private def getLiveContainerCount(invocationNamespace: String,
                                    fqn: FullyQualifiedEntityName,
                                    revision: DocRevision): Future[Long] = {
    val namespacePrefix = containerPrefix(ContainerKeys.namespacePrefix, invocationNamespace, fqn, Some(revision))
    val inProgressPrefix = containerPrefix(ContainerKeys.inProgressPrefix, invocationNamespace, fqn, Some(revision))
    val warmedPrefix = containerPrefix(ContainerKeys.warmedPrefix, invocationNamespace, fqn, Some(revision))
    for {
      namespaceCount <- etcdClient.getCount(namespacePrefix)
      inProgressCount <- etcdClient.getCount(inProgressPrefix)
      warmedCount <- etcdClient.getCount(warmedPrefix)
    } yield {
      namespaceCount + inProgressCount + warmedCount
    }
  }

  /** Initialize message consumers */
  private val msgProvider = SpiLoader.get[MessagingProvider]
  //number of peeked messages - increasing the concurrentPeekFactor improves concurrent usage, but adds risk for message loss in case of crash
  private val maxPeek = loadConfigOrThrow[Int](ConfigKeys.containerCreationMaxPeek)
  private var consumer: Option[ContainerMessageConsumer] = Some(
    new ContainerMessageConsumer(
      instance,
      pool,
      entityStore,
      cfg,
      msgProvider,
      longPollDuration = 1.second,
      maxPeek,
      sendAckToScheduler))

  override def enable(): String = {
    invokerHealthManager ! Enable
    pool ! Enable
    warmUp()
    s"${instance.toString} is now enabled."
  }

  override def disable(): String = {
    invokerHealthManager ! GracefulShutdown
    pool ! GracefulShutdown
    warmUpWatcher.foreach(_.close())
    warmUpWatcher = None
    s"${instance.toString} is now disabled."
  }

  override def getPoolState(): Future[Either[NotSupportedPoolState, TotalContainerPoolState]] = {
    implicit val timeout: Timeout = 5.seconds
    (pool ? GetState).mapTo[TotalContainerPoolState].map(Right(_))
  }

  override def isEnabled(): String = {
    InvokerEnabled(warmUpWatcher.nonEmpty).serialize()
  }

  override def backfillPrewarm(): String = {
    pool ! AdjustPrewarmedContainer
    "backfilling prewarm container"
  }

  private val warmUpFetchRequest = FetchRequest(
    TransactionId(TransactionId.generateTid()).serialize,
    InvokerHealthManager.healthActionIdentity.namespace.name.asString,
    WarmUp.warmUpAction.serialize,
    DocRevision.empty.serialize) // a warm up fetch request which contains nothing

  // warm up grpc connection with scheduler
  private def warmUpScheduler(scheduler: SchedulerEndpoints) = {
    val setting =
      GrpcClientSettings
        .connectToServiceAt(scheduler.host, scheduler.rpcPort)
        .withTls(grpcConfig.tls)
    val client = ActivationServiceClient(setting)
    client.fetchActivation(warmUpFetchRequest).andThen {
      case _ =>
        logging.info(this, s"Warmed up scheduler $scheduler")
        client.close()
    }
  }

  private def warmUp(): Unit = {
    implicit val transId = TransactionId.warmUp
    if (warmUpWatcher.isEmpty)
      warmUpWatcher = Some(etcdClient.watch(SchedulerKeys.prefix, true) { res: WatchUpdate =>
        res.getEvents.asScala.foreach {
          event =>
            event.getType match {
              case EventType.DELETE =>
                val key = event.getPrevKv.getKey
                warmedSchedulers.remove(key)
              case EventType.PUT =>
                val key = event.getKv.getKey
                val value = event.getKv.getValue
                SchedulerStates
                  .parse(value)
                  .map { state =>
                    // warm up new scheduler
                    warmedSchedulers.getOrElseUpdate(key, {
                      logging.info(this, s"Warm up scheduler ${state.sid}")
                      warmUpScheduler(state.endpoints)
                      value
                    })
                  }
              case _ =>
            }
        }
      })

    etcdClient.getPrefix(SchedulerKeys.prefix).map { res =>
      res.getKvsList.asScala.map { kv =>
        val scheduler = kv.getKey
        warmedSchedulers.getOrElseUpdate(
          scheduler, {
            logging.info(this, s"Warm up scheduler $scheduler")
            SchedulerStates
              .parse(kv.getValue)
              .map { state =>
                warmUpScheduler(state.endpoints)
              }
              .recover {
                case t =>
                  logging.error(this, s"Unexpected error $t")
              }

            kv.getValue
          })

      }
    }
  }
  warmUp()
}

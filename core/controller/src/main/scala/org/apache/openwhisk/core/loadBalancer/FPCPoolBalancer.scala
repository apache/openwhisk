package org.apache.openwhisk.core.loadBalancer

import java.nio.charset.StandardCharsets
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.LongAdder

import akka.actor.{Actor, ActorRef, ActorRefFactory, ActorSystem, Cancellable, Props}
import akka.event.Logging.InfoLevel
import akka.pattern.ask
import akka.util.Timeout
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.openwhisk.common.InvokerState.{Healthy, Offline, Unhealthy}
import org.apache.openwhisk.common._
import org.apache.openwhisk.core.connector._
import org.apache.openwhisk.core.controller.Controller
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.etcd.EtcdKV.{InvokerKeys, QueueKeys, SchedulerKeys, ThrottlingKeys}
import org.apache.openwhisk.core.etcd.EtcdType._
import org.apache.openwhisk.core.etcd.{EtcdClient, EtcdConfig}
import org.apache.openwhisk.core.scheduler.queue.{CreateQueue, CreateQueueResponse, QueueManager}
import org.apache.openwhisk.core.scheduler.{SchedulerEndpoints, SchedulerStates}
import org.apache.openwhisk.core.service._
import org.apache.openwhisk.core.{ConfigKeys, WarmUp, WhiskConfig}
import org.apache.openwhisk.spi.SpiLoader
import org.apache.openwhisk.utils.retry
import pureconfig._
import pureconfig.generic.auto._

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future, Promise}
import scala.language.postfixOps
import scala.util.{Failure, Random, Success, Try}

class FPCPoolBalancer(config: WhiskConfig,
                      controllerInstance: ControllerInstanceId,
                      etcdClient: EtcdClient,
                      private val feedFactory: FeedFactory,
                      lbConfig: ShardingContainerPoolBalancerConfig =
                        loadConfigOrThrow[ShardingContainerPoolBalancerConfig](ConfigKeys.loadbalancer),
                      private val messagingProvider: MessagingProvider = SpiLoader.get[MessagingProvider])(
  implicit val actorSystem: ActorSystem,
  logging: Logging)
    extends LoadBalancer {

  private implicit val executionContext: ExecutionContext = actorSystem.dispatcher
  private implicit val requestTimeout: Timeout = Timeout(5.seconds)

  private val entityStore = WhiskEntityStore.datastore()

  private val clusterName = loadConfigOrThrow[String](ConfigKeys.whiskClusterName)

  /** key: SchedulerEndpoints, value: SchedulerStates */
  private val schedulerEndpoints = TrieMap[SchedulerEndpoints, SchedulerStates]()
  private val messageProducer = messagingProvider.getProducer(config, Some(ActivationEntityLimit.MAX_ACTIVATION_LIMIT))

  private val watcherName = "distributed-pool-balancer"
  val watcherService: ActorRef = actorSystem.actorOf(WatcherService.props(etcdClient))

  /** State related to invocations and throttling */
  protected[loadBalancer] val activationSlots = TrieMap[ActivationId, DistributedActivationEntry]()
  protected[loadBalancer] val activationPromises =
    TrieMap[ActivationId, Promise[Either[ActivationId, WhiskActivation]]]()

  /** key: queue/${invocationNs}/ns/action/leader, value: SchedulerEndpoints */
  private val queueEndpoints = TrieMap[String, SchedulerEndpoints]()

  private val activationsPerNamespace = TrieMap[UUID, LongAdder]()
  private val activationsPerController = TrieMap[ControllerInstanceId, LongAdder]()
  private val totalActivations = new LongAdder()
  private val totalActivationMemory = new LongAdder()
  private val throttlers = TrieMap[String, Boolean]()

  /**
   * Publishes activation message on internal bus for an invoker to pick up.
   *
   * @param action the action to invoke
   * @param msg the activation message to publish on an invoker topic
   * @param transid the transaction id for the request
   * @return result a nested Future the outer indicating completion of publishing and
   *         the inner the completion of the action (i.e., the result)
   *         if it is ready before timeout (Right) otherwise the activation id (Left).
   *         The future is guaranteed to complete within the declared action time limit
   *         plus a grace period (see activeAckTimeoutGrace).
   */
  override def publish(action: ExecutableWhiskActionMetaData, msg: ActivationMessage)(
    implicit transid: TransactionId): Future[Future[Either[ActivationId, WhiskActivation]]] = {

    val topicBaseName = if (schedulerEndpoints.isEmpty) {
      logging.error(
        this,
        s"Failed to invoke action ${action.fullyQualifiedName(false)}, error: no scheduler endpoint available")
      Future.failed(LoadBalancerException("No scheduler endpoint available"))
    } else {
      val invocationNamespace = msg.user.namespace.name.asString
      val key = QueueKeys.queue(invocationNamespace, action.fullyQualifiedName(false), true)

      queueEndpoints.get(key) match {
        case Some(endPoint) =>
          Future.successful(
            schedulerEndpoints.getOrElse(endPoint, Random.shuffle(schedulerEndpoints.toList).head._2).sid.toString)
        case None =>
          etcdClient
            .get(key)
            .map { res =>
              res.getKvsList.asScala.headOption map { kv =>
                val endPoint: String = kv.getValue
                SchedulerEndpoints
                  .parse(endPoint)
                  .map { endPoint =>
                    queueEndpoints.update(kv.getKey, endPoint)
                    Some(
                      schedulerEndpoints
                        .getOrElse(endPoint, Random.shuffle(schedulerEndpoints.toList).head._2)
                        .sid
                        .toString)
                  }
                  .getOrElse {
                    FPCPoolBalancer.schedule(schedulerEndpoints.values.toIndexedSeq).map { scheduler =>
                      createQueue(invocationNamespace, action.toWhiskAction, msg.action, msg.revision, scheduler)
                      scheduler.sid.toString
                    }
                  }
              } getOrElse {
                FPCPoolBalancer.schedule(schedulerEndpoints.values.toIndexedSeq).map { scheduler =>
                  createQueue(invocationNamespace, action.toWhiskAction, msg.action, msg.revision, scheduler)
                  scheduler.sid.toString
                }
              }
            }
            .map { _.get }
            .recoverWith {
              case _ =>
                Future.failed(LoadBalancerException("No scheduler endpoint available"))
            }
      }
    }
    topicBaseName.flatMap { baseName =>
      val topicName = Controller.topicPrefix + baseName
      val activationResult = setupActivation(msg, action)
      sendActivationToKafka(messageProducer, msg, topicName).map(_ => activationResult)
    }
  }

  private def createQueue(
    invocationNamespace: String,
    actionMetaData: WhiskActionMetaData,
    fullyQualifiedEntityName: FullyQualifiedEntityName,
    revision: DocRevision,
    scheduler: SchedulerStates,
    retryCount: Int = 5,
    excludeSchedulers: Set[SchedulerInstanceId] = Set.empty)(implicit transid: TransactionId): Unit = {
    if (retryCount >= 0)
      scheduler
        .getRemoteRef(QueueManager.actorName)
        .ask(CreateQueue(invocationNamespace, fullyQualifiedEntityName.copy(binding = None), revision, actionMetaData))
        .mapTo[CreateQueueResponse]
        .onComplete {
          case Success(_) =>
            logging.info(
              this,
              s"Created queue successfully for $invocationNamespace/$fullyQualifiedEntityName on ${scheduler.sid}")
          case Failure(t) =>
            logging.error(
              this,
              s"failed to get response from ${scheduler}, error is $t, will retry for ${retryCount} times")
            // try another scheduler
            FPCPoolBalancer
              .schedule(schedulerEndpoints.values.toIndexedSeq, excludeSchedulers + scheduler.sid)
              .map { newScheduler =>
                createQueue(
                  invocationNamespace,
                  actionMetaData,
                  fullyQualifiedEntityName,
                  revision,
                  newScheduler,
                  retryCount - 1,
                  excludeSchedulers + scheduler.sid)
              }
              .getOrElse {
                logging.error(
                  this,
                  s"failed to create queue for $invocationNamespace/$fullyQualifiedEntityName, no scheduler endpoint available related activations may fail")
              }
        } else
      logging.error(
        this,
        s"failed to create queue for $invocationNamespace/$fullyQualifiedEntityName, related activations may fail")
  }

  /**
   * 2. Update local state with the to be executed activation.
   *
   * All activations are tracked in the activationSlots map. Additionally, blocking invokes
   * are tracked in the activation results map. When a result is received via activeack, it
   * will cause the result to be forwarded to the caller waiting on the result, and cancel
   * the DB poll which is also trying to do the same.
   */
  private def setupActivation(msg: ActivationMessage,
                              action: ExecutableWhiskActionMetaData): Future[Either[ActivationId, WhiskActivation]] = {
    val isBlackboxInvocation = action.exec.pull

    totalActivations.increment()
    totalActivationMemory.add(action.limits.memory.megabytes)
    activationsPerNamespace.getOrElseUpdate(msg.user.namespace.uuid, new LongAdder()).increment()
    activationsPerController.getOrElseUpdate(controllerInstance, new LongAdder()).increment()

    // Timeout is a multiple of the configured maximum action duration. The minimum timeout is the configured standard
    // value for action durations to avoid too tight timeouts.
    // Timeouts in general are diluted by a configurable factor. In essence this factor controls how much slack you want
    // to allow in your topics before you start reporting failed activations.
    val timeout = (action.limits.timeout.duration.max(TimeLimit.STD_DURATION) * lbConfig.timeoutFactor) + 1.minute

    val resultPromise = if (msg.blocking) {
      activationPromises.getOrElseUpdate(msg.activationId, Promise[Either[ActivationId, WhiskActivation]]()).future
    } else Future.successful(Left(msg.activationId))

    // Install a timeout handler for the catastrophic case where an active ack is not received at all
    // (because say an invoker is down completely, or the connection to the message bus is disrupted) or when
    // the active ack is significantly delayed (possibly dues to long queues but the subject should not be penalized);
    // in this case, if the activation handler is still registered, remove it and update the books.
    activationSlots.getOrElseUpdate(
      msg.activationId, {
        val timeoutHandler = actorSystem.scheduler.scheduleOnce(timeout) {
          processCompletion(msg.activationId, msg.transid, forced = true, isSystemError = false)
        }

        // please note: timeoutHandler.cancel must be called on all non-timeout paths, e.g. Success
        DistributedActivationEntry(
          msg.activationId,
          msg.user.namespace.uuid,
          msg.user.namespace.name.asString,
          msg.revision,
          msg.transid,
          action.limits.memory.megabytes.MB,
          action.limits.concurrency.maxConcurrent,
          action.fullyQualifiedName(true),
          timeoutHandler,
          isBlackboxInvocation,
          msg.blocking,
          controllerInstance)
      })

    resultPromise
  }

  /** 3. Send the activation to the kafka */
  private def sendActivationToKafka(producer: MessageProducer,
                                    msg: ActivationMessage,
                                    topic: String): Future[RecordMetadata] = {
    implicit val transid: TransactionId = msg.transid

    MetricEmitter.emitCounterMetric(LoggingMarkers.LOADBALANCER_ACTIVATION_START)
    val start = transid.started(this, LoggingMarkers.CONTROLLER_KAFKA)

    producer.send(topic, msg).andThen {
      case Success(status) =>
        transid.finished(
          this,
          start,
          s"posted to ${status.topic()}[${status.partition()}][${status.offset()}]",
          logLevel = InfoLevel)
      case Failure(_) => transid.failed(this, start, s"error on posting to topic $topic")
    }
  }

  /**
   * Subscribes to active acks (completion messages from the invokers), and
   * registers a handler for received active acks from invokers.
   */
  private val activationFeed: ActorRef =
    feedFactory.createFeed(actorSystem, messagingProvider, processAcknowledgement)

  /** 4. Get the active-ack message and parse it */
  protected[loadBalancer] def processAcknowledgement(bytes: Array[Byte]): Future[Unit] = Future {
    val raw = new String(bytes, StandardCharsets.UTF_8)
    AcknowledegmentMessage.parse(raw) match {
      case Success(acknowledegment) =>
        acknowledegment.isSlotFree.foreach { invoker =>
          processCompletion(
            acknowledegment.activationId,
            acknowledegment.transid,
            forced = false,
            isSystemError = acknowledegment.isSystemError.getOrElse(false))
        }

        acknowledegment.result.foreach { response =>
          processResult(acknowledegment.activationId, acknowledegment.transid, response)
        }

        activationFeed ! MessageFeed.Processed
      case Failure(t) =>
        activationFeed ! MessageFeed.Processed
        logging.error(this, s"failed processing message: $raw")

      case _ =>
        activationFeed ! MessageFeed.Processed
        logging.warn(this, s"Unexpected Acknowledgment message received by loadbalancer: $raw")
    }
  }

  private def renewTimeoutHandler(entry: DistributedActivationEntry,
                                  msg: ActivationMessage,
                                  isSystemError: Boolean): Unit = {
    entry.timeoutHandler.cancel()

    val timeout = (TimeLimit.MAX_DURATION * lbConfig.timeoutFactor) + 1.minute
    val timeoutHandler = actorSystem.scheduler.scheduleOnce(timeout) {
      processCompletion(msg.activationId, msg.transid, forced = true, isSystemError)
    }
    activationSlots.update(msg.activationId, entry.copy(timeoutHandler = timeoutHandler))
  }

  /** 5. Process the result ack and return it to the user */
  private def processResult(aid: ActivationId,
                            tid: TransactionId,
                            response: Either[ActivationId, WhiskActivation]): Unit = {
    // Resolve the promise to send the result back to the user.
    // The activation will be removed from the activation slots later, when the completion message
    // is received (because the slot in the invoker is not yet free for new activations).
    activationPromises.remove(aid).foreach(_.trySuccess(response))
    logging.info(this, s"received result ack for '$aid'")(tid)
  }

  private def deleteActivationSlot(aid: ActivationId, tid: TransactionId): Option[DistributedActivationEntry] = {
    activationSlots.remove(aid) match {
      case option =>
        if (activationSlots.contains(aid)) {
          logging.warn(this, s"Failed to delete $aid from activation slots")(tid)
          throw new Exception(s"Failed to delete $aid from activation slots")
        }
        option
    }
  }

  /** Process the completion ack and update the state */
  protected[loadBalancer] def processCompletion(aid: ActivationId,
                                                tid: TransactionId,
                                                forced: Boolean,
                                                isSystemError: Boolean): Unit = {
    implicit val transid = tid
    activationSlots.remove(aid) match {
      case Some(entry) =>
        if (activationSlots.contains(aid))
          Try { retry(deleteActivationSlot(aid, tid)) } recover {
            case _ =>
              logging.error(this, s"Failed to delete $aid from activation slots")
          }
        totalActivations.decrement()
        totalActivationMemory.add(entry.memory.toMB * (-1))
        activationsPerNamespace.get(entry.namespaceId).foreach(_.decrement())
        activationsPerController.get(entry.controllerName).foreach(_.decrement())

        if (!forced) {
          entry.timeoutHandler.cancel()
          // notice here that the activationPromises is not touched, because the expectation is that
          // the active ack is received as expected, and processing that message removed the promise
          // from the corresponding map
          logging.info(this, s"received completion ack for '$aid', system error=$isSystemError")(tid)
        } else {
          logging.error(this, s"Failed to invoke action ${aid.toString}, error: timeout waiting for the active ack")

          // the entry has timed out; if the active ack is still around, remove its entry also
          // and complete the promise with a failure if necessary
          activationPromises
            .remove(aid)
            .foreach(_.tryFailure(new Throwable("Activation entry has timed out, no completion or active ack received yet")))
        }

      // Active acks that are received here are strictly from user actions - health actions are not part of
      // the load balancer's activation map. Inform the invoker pool supervisor of the user action completion.
      case None if !forced =>
        // Received a completion ack that has already been taken out of the state because of a timeout (forced ack).
        // The result is ignored because a timeout has already been reported to the invokerPool per the force.
        // Logging this condition as a warning because the invoker processed the activation and sent a completion
        // message - but not in time.
        logging.warn(this, s"received completion ack for '$aid' which has no entry, system error=$isSystemError")(tid)
      case None =>
        // The entry has already been removed by a completion ack. This part of the code is reached by the timeout and can
        // happen if completion ack and timeout happen roughly at the same time (the timeout was triggered before the completion
        // ack canceled the timer). As the completion ack is already processed we don't have to do anything here.
        logging.debug(this, s"forced completion ack for '$aid' which has no entry")(tid)
    }
  }

  private val queueKey = QueueKeys.queuePrefix
  private val schedulerKey = SchedulerKeys.prefix
  private val throttlingKey = ThrottlingKeys.prefix
  private val watchedKeys = Set(queueKey, schedulerKey, throttlingKey)

  private val watcher = actorSystem.actorOf(Props(new Actor {
    watchedKeys.foreach { key =>
      watcherService ! WatchEndpoint(key, "", true, watcherName, Set(PutEvent, DeleteEvent))
    }

    override def receive: Receive = {
      case WatchEndpointRemoved(watchKey, key, value, true) =>
        watchKey match {
          case `queueKey` =>
            if (key.contains("leader")) {
              queueEndpoints.remove(key)
              activationSlots.values
                .find(entry =>
                  //the leader key's value is queue/invocationNamespace/ns/pkg/act/leader
                  QueueKeys
                    .queue(entry.invocationNamespace, entry.fullyQualifiedEntityName.copy(version = None), true) == key)
                .foreach {
                  entry =>
                    implicit val transid = entry.transactionId
                    logging.warn(
                      this,
                      s"The $key is deleted from ETCD, but there are still unhandled activations for this action, try to create a new queue")
                    WhiskActionMetaData
                      .get(
                        entityStore,
                        entry.fullyQualifiedEntityName.toDocId,
                        DocRevision.empty,
                        entry.revision != DocRevision.empty)
                      .map { actionMetaData =>
                        FPCPoolBalancer
                          .schedule(schedulerEndpoints.values.toIndexedSeq)
                          .map { scheduler =>
                            createQueue(
                              entry.invocationNamespace,
                              actionMetaData,
                              entry.fullyQualifiedEntityName,
                              entry.revision,
                              scheduler)
                          }
                          .getOrElse {
                            logging.error(
                              this,
                              s"Failed to recreate queue for ${entry.fullyQualifiedEntityName}, no scheduler endpoint available")
                          }
                      }
                }
            }
          case `schedulerKey` =>
            SchedulerStates
              .parse(value)
              .map { state =>
                logging.info(this, s"remove scheduler endpoint $state")
                schedulerEndpoints.remove(state.endpoints)
              }
              .recover {
                case t =>
                  logging.error(this, s"Unexpected error$t")
              }

          case `throttlingKey` =>
            throttlers.remove(key)
          case _ =>
        }

      case WatchEndpointInserted(watchKey, key, value, true) =>
        watchKey match {
          case `queueKey` =>
            //ignore parse follower value, just parse leader value's normal value, e.g. on special case, leader key's value may be Removing
            if (key.contains("leader") && value.contains("host")) {
              SchedulerEndpoints
                .parse(value)
                .map { endpoints =>
                  queueEndpoints.update(key, endpoints)
                }
                .recover {
                  case t =>
                    logging.error(this, s"Unexpected error$t")
                }
            }
          case `schedulerKey` =>
            SchedulerStates
              .parse(value)
              .map { state =>
                // if this is a new scheduler, warm up it
                if (!schedulerEndpoints.contains(state.endpoints))
                  warmUpScheduler(key, state.endpoints)
                schedulerEndpoints.update(state.endpoints, state)
              }
              .recover {
                case t =>
                  logging.error(this, s"Unexpected error$t")
              }
          case `throttlingKey` =>
            val throttled = Try {
              value.toBoolean
            }.getOrElse(false)
            throttlers.update(key, throttled)
          case _ =>
        }
    }
  }))

  private[loadBalancer] def getSchedulerEndpoint() = {
    schedulerEndpoints
  }

  private val warmUpQueueCreationRequest =
    ExecManifest.runtimesManifest
      .resolveDefaultRuntime("nodejs:default")
      .map { manifest =>
        val metadata = ExecutableWhiskActionMetaData(
          WarmUp.warmUpAction.path,
          WarmUp.warmUpAction.name,
          CodeExecMetaDataAsString(manifest, false, entryPoint = None))
        CreateQueue(
          WarmUp.warmUpActionIdentity.namespace.name.asString,
          WarmUp.warmUpAction,
          DocRevision.empty,
          metadata.toWhiskAction)
      }

  private def warmUpScheduler(schedulerName: String, scheduler: SchedulerEndpoints): Unit = {
    implicit val transId = TransactionId.warmUp
    logging.info(this, s"Warm up scheduler $scheduler")
    sendActivationToKafka(
      messageProducer,
      WarmUp.warmUpActivation(controllerInstance),
      Controller.topicPrefix + schedulerName.replace(s"$clusterName/", "").replace("/", "")) // warm up kafka

    warmUpQueueCreationRequest.foreach { request =>
      scheduler.getRemoteRef(QueueManager.actorName).ask(request).mapTo[CreateQueueResponse].onComplete {
        case _ => logging.info(this, s"Warmed up scheduler $scheduler")
      }
    }
  }

  protected def loadSchedulerEndpoint(): Unit = {
    etcdClient
      .getPrefix(SchedulerKeys.prefix)
      .map { res =>
        res.getKvsList.asScala.map { kv =>
          val schedulerStates: String = kv.getValue
          SchedulerStates
            .parse(schedulerStates)
            .map { state =>
              // if this is a new scheduler, warm up it
              if (!schedulerEndpoints.contains(state.endpoints))
                warmUpScheduler(kv.getKey, state.endpoints)
              schedulerEndpoints.update(state.endpoints, state)
            }
            .recover {
              case t =>
                logging.error(this, s"Unexpected error$t")
            }
        }
      }
  }

  loadSchedulerEndpoint()

  override def close(): Unit = {
    watchedKeys.foreach { key =>
      watcherService ! UnwatchEndpoint(key, true, watcherName)
    }
    activationFeed ! GracefulShutdown
  }

  /**
   * Returns a message indicating the health of the containers and/or container pool in general.
   *
   * @return a Future[IndexedSeq[InvokerHealth]] representing the health of the pools managed by the loadbalancer.
   **/
  override def invokerHealth(): Future[IndexedSeq[InvokerHealth]] = {
    etcdClient.getPrefix(s"${InvokerKeys.prefix}/").map { res =>
      val healthsFromEtcd = res.getKvsList.asScala.map { kv =>
        val (memory, busyMemory, status, tags, dedicatedNamespaces) = InvokerResourceMessage
          .parse(kv.getValue.toString(StandardCharsets.UTF_8))
          .map { resourceMessage =>
            val status = resourceMessage.status match {
              case Healthy.asString   => Healthy
              case Unhealthy.asString => Unhealthy
              case Offline.asString   => Offline
            }
            (
              resourceMessage.freeMemory.MB,
              resourceMessage.busyMemory.MB,
              status,
              resourceMessage.tags,
              resourceMessage.dedicatedNamespaces)
          }
          .get
        val temporalId = InvokerKeys.getInstanceId(kv.getKey.toString(StandardCharsets.UTF_8))
        val invoker = temporalId.copy(
          userMemory = memory,
          busyMemory = Some(busyMemory),
          tags = tags,
          dedicatedNamespaces = dedicatedNamespaces)

        new InvokerHealth(invoker, status)
      }.toIndexedSeq
      val missingHealths =
        if (healthsFromEtcd.isEmpty) Set.empty[InvokerHealth]
        else
          ((0 to healthsFromEtcd.maxBy(_.id.toInt).id.toInt).toSet -- healthsFromEtcd.map(_.id.toInt))
            .map(id => new InvokerHealth(InvokerInstanceId(id, Some(id.toString), userMemory = 0 MB), Offline))
      (healthsFromEtcd ++ missingHealths) sortBy (_.id.toInt)
    }
  }

  /** Gets the number of in-flight activations for a specific user. */
  override def activeActivationsFor(namespace: UUID): Future[Int] =
    Future.successful(activationsPerNamespace.get(namespace).map(_.intValue()).getOrElse(0))

  /** Gets the number of in-flight activations for a specific controller. */
  override def activeActivationsByController(controller: String): Future[Int] =
    Future.successful(activationsPerController.get(ControllerInstanceId(controller)).map(_.intValue()).getOrElse(0))

  /** Gets the in-flight activations */
  override def activeActivationsByController: Future[List[ActivationId]] =
    Future.successful(activationSlots.keySet.toList)

  /** Gets the number of in-flight activations for a specific invoker. */
  override def activeActivationsByInvoker(invoker: String): Future[Int] = Future.successful(0)

  /** Gets the number of in-flight activations in the system. */
  override def totalActiveActivations: Future[Int] = Future.successful(totalActivations.intValue())

  /** Gets the throttling for given action. */
  override def checkThrottle(namespace: EntityPath, fqn: String): Boolean = {

    /**
     * Note! The throttle key is assumed to exist unconditionally and is not limited to throttle if not present.
     *
     * Action Throttle true      -> 429
     *                 false     -> Pass (wait 429, Container already exist)
     *                 not exist -> Namespace Throttled true      -> 429 (Cannot create more containers)
     *                                                  false     -> Pass
     *                                                  not exist -> Pass
     */
    throttlers.getOrElse(
      ThrottlingKeys.action(namespace.namespace, fqn),
      throttlers.getOrElse(ThrottlingKeys.namespace(namespace.root), false))
  }
}

object FPCPoolBalancer extends LoadBalancerProvider {

  override def instance(whiskConfig: WhiskConfig, instance: ControllerInstanceId)(implicit actorSystem: ActorSystem,
                                                                                  logging: Logging): LoadBalancer = {

    implicit val exe: ExecutionContextExecutor = actorSystem.dispatcher
    val activeAckTopic = s"${Controller.topicPrefix}completed${instance.asString}"
    val maxActiveAcksPerPoll = 128
    val activeAckPollDuration = 1.second

    val feedFactory = new FeedFactory {
      def createFeed(f: ActorRefFactory, provider: MessagingProvider, acker: Array[Byte] => Future[Unit]): ActorRef = {
        f.actorOf(Props {
          new MessageFeed(
            "activeack",
            logging,
            provider.getConsumer(whiskConfig, activeAckTopic, activeAckTopic, maxPeek = maxActiveAcksPerPoll),
            maxActiveAcksPerPoll,
            activeAckPollDuration,
            acker)
        })
      }
    }

    val etcd = EtcdClient(loadConfigOrThrow[EtcdConfig](ConfigKeys.etcd).hosts)

    new FPCPoolBalancer(whiskConfig, instance, etcd, feedFactory)
  }

  def requiredProperties: Map[String, String] = WhiskConfig.kafkaHosts

  // TODO modularize rng algorithm
  /**
   * The rng algorithm is responsible for the invoker distribution, and the better the distribution, the smaller the number of rescheduling.
   *
   */
  def rng(mod: Int): Int = ThreadLocalRandom.current().nextInt(mod)

  /**
   * Assign a scheduler to a message, return the scheduler which has least queues
   *
   * @param schedulers Scheduler pool
   * @param excludeSchedulers schedulers which should not be chose
   * @return Assigned a scheduler
   */
  def schedule(schedulers: IndexedSeq[SchedulerStates],
               excludeSchedulers: Set[SchedulerInstanceId] = Set.empty): Option[SchedulerStates] = {
    schedulers.filter(scheduler => !excludeSchedulers.contains(scheduler.sid)).sortBy(_.queueSize).headOption
  }

}

/**
 * State kept for each activation slot until completion.
 *
 * @param id id of the activation
 * @param namespaceId namespace that invoked the action
 * @param timeoutHandler times out completion of this activation, should be canceled on good paths
 */
case class DistributedActivationEntry(id: ActivationId,
                                      namespaceId: UUID,
                                      invocationNamespace: String,
                                      revision: DocRevision,
                                      transactionId: TransactionId,
                                      memory: ByteSize,
                                      maxConcurrent: Int,
                                      fullyQualifiedEntityName: FullyQualifiedEntityName,
                                      timeoutHandler: Cancellable,
                                      isBlackbox: Boolean,
                                      isBlocking: Boolean,
                                      controllerName: ControllerInstanceId = ControllerInstanceId("0"))

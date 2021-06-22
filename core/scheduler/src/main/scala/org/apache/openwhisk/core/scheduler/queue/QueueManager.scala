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

package org.apache.openwhisk.core.scheduler.queue

import java.nio.charset.StandardCharsets
import java.time.Instant

import akka.actor.{Actor, ActorRef, ActorRefFactory, ActorSelection, PoisonPill, Props}
import akka.pattern.ask
import akka.util.Timeout
import org.apache.openwhisk.common._
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.WarmUp.isWarmUpAction
import org.apache.openwhisk.core.ack.ActiveAck
import org.apache.openwhisk.core.connector._
import org.apache.openwhisk.core.containerpool.Interval
import org.apache.openwhisk.core.database.{ArtifactStore, DocumentRevisionMismatchException, UserContext}
import org.apache.openwhisk.core.entity.{ActivationResponse => OriginActivationResponse, _}
import org.apache.openwhisk.core.etcd.EtcdKV.{QueueKeys, SchedulerKeys}
import org.apache.openwhisk.core.etcd.{EtcdClient, EtcdFollower, EtcdLeader}
import org.apache.openwhisk.core.scheduler.{SchedulerEndpoints, SchedulerStates}
import org.apache.openwhisk.core.service._
import pureconfig.loadConfigOrThrow
import spray.json.{DefaultJsonProtocol, _}

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Try}
import pureconfig.generic.auto._

object QueueSize
case class MemoryQueueKey(invocationNamespace: String, docInfo: DocInfo)
case class MemoryQueueValue(queue: ActorRef, isLeader: Boolean)
case class UpdateMemoryQueue(oldAction: DocInfo,
                             newAction: FullyQualifiedEntityName,
                             activationMessage: ActivationMessage)
case class CreateNewQueue(activationMessage: ActivationMessage,
                          action: FullyQualifiedEntityName,
                          actionMetadata: WhiskActionMetaData)

case class QueueManagerConfig(maxRetriesToGetQueue: Int, maxSchedulingTime: FiniteDuration)

class QueueManager(
  entityStore: ArtifactStore[WhiskEntity],
  getWhiskActionMetaData: (ArtifactStore[WhiskEntity], DocId, DocRevision, Boolean) => Future[WhiskActionMetaData],
  etcdClient: EtcdClient,
  schedulerEndpoints: SchedulerEndpoints,
  schedulerId: SchedulerInstanceId,
  dataManagementService: ActorRef,
  watcherService: ActorRef,
  ack: ActiveAck,
  store: (TransactionId, WhiskActivation, UserContext) => Future[Any],
  childFactory: (ActorRefFactory, String, FullyQualifiedEntityName, DocRevision, WhiskActionMetaData) => ActorRef,
  schedulerConsumer: MessageConsumer,
  queueManagerConfig: QueueManagerConfig = loadConfigOrThrow[QueueManagerConfig](ConfigKeys.schedulerQueueManager))(
  implicit logging: Logging)
    extends Actor {

  val maxPeek = loadConfigOrThrow[Int](ConfigKeys.schedulerMaxPeek)

  /** key: leader-key, value:DocRevision */
  private val initRevisionMap = TrieMap[String, DocRevision]()

  private val actorSelectionMap = TrieMap[String, ActorSelection]()

  private val leaderElectionCallbacks = TrieMap[String, Either[EtcdFollower, EtcdLeader] => Unit]()

  private implicit val askTimeout = Timeout(5.seconds)
  private implicit val ec = context.dispatcher
  private implicit val system = context.system

  private val watcherName = "queue-manager"
  // watch leaders and register them into actorSelectionMap
  watcherService ! WatchEndpoint(QueueKeys.queuePrefix, "", isPrefix = true, watcherName, Set(PutEvent, DeleteEvent))

  override def receive: Receive = {
    case request: CreateQueue if isWarmUpAction(request.fqn) =>
      logging.info(
        this,
        s"The ${request.fqn} action is an action used to connect a network level connection. So drop the message without creating a queue.")
      sender ! CreateQueueResponse(request.invocationNamespace, request.fqn, success = true)

    // note: action sent from the pool balancer already includes version
    case request: CreateQueue =>
      val receiver = sender
      QueuePool.get(MemoryQueueKey(request.invocationNamespace, request.fqn.toDocId.asDocInfo(request.revision))) match {
        case Some(_) =>
          logging.info(this, s"Queue already exist for ${request.invocationNamespace}/${request.fqn}")
          receiver ! CreateQueueResponse(request.invocationNamespace, request.fqn, success = true)

        case None =>
          logging.info(this, s"Trying to create queue for ${request.invocationNamespace}/${request.fqn}")
          electLeaderAndCreateQueue(request, Some(receiver))
      }

    case msg: ElectionResult =>
      msg.leadership match {
        case Right(EtcdLeader(key, value, lease)) =>
          leaderElectionCallbacks.remove(key).foreach { callback =>
            callback(Right(EtcdLeader(key, value, lease)))
          }

        case Left(EtcdFollower(key, value)) =>
          leaderElectionCallbacks.remove(key).foreach { callback =>
            callback(Left(EtcdFollower(key, value)))
          }
      }

    case msg: ActivationMessage =>
      logging.info(
        this,
        s"Got activation message ${msg.activationId} for ${msg.user.namespace}/${msg.action} from remote queue manager.")(
        msg.transid)

      handleActivationMessage(msg)

    case UpdateMemoryQueue(oldAction, newAction, msg) =>
      logging.info(
        this,
        s"[${msg.activationId}] Update the memory queue for ${newAction.namespace}/${newAction.name}, old rev: ${oldAction.rev} new rev: ${msg.revision}, activationId: ${msg.activationId.asString}")
      implicit val transid = msg.transid
      QueuePool.get(MemoryQueueKey(msg.user.namespace.name.asString, oldAction)) match {
        case Some(memoryQueueValue) =>
          QueuePool.put(
            MemoryQueueKey(msg.user.namespace.name.asString, oldAction),
            MemoryQueueValue(memoryQueueValue.queue, false))
          memoryQueueValue.queue ! StopSchedulingAsOutdated

        case _ =>
        // do nothing because we will anyway create a new one
      }
      createNewQueue(newAction, msg)

    case CreateNewQueue(msg, action, actionMetaData) =>
      val memoryQueueKey = MemoryQueueKey(msg.user.namespace.name.asString, action.toDocId.asDocInfo(msg.revision))
      QueuePool.get(memoryQueueKey) match {
        case Some(queue) if queue.isLeader =>
          queue.queue ! msg
          logging.info(this, s"Queue for action $action is already updated, skip")(msg.transid)
        case _ =>
          val queue =
            childFactory(context, msg.user.namespace.name.asString, action, msg.revision, actionMetaData)
          queue ! VersionUpdated
          QueuePool.put(
            MemoryQueueKey(msg.user.namespace.name.asString, action.toDocId.asDocInfo(msg.revision)),
            MemoryQueueValue(queue, true))
          updateInitRevisionMap(getLeaderKey(msg.user.namespace.name.asString, msg.action), msg.revision)
          queue ! msg
          msg.transid.mark(this, LoggingMarkers.SCHEDULER_QUEUE_CREATE)
      }

    // leaderKey is now optional, it becomes None when the stale queue is removed
    case QueueRemoved(invocationNamespace, action, leaderKey) =>
      (QueuePool.remove(MemoryQueueKey(invocationNamespace, action)), leaderKey) match {
        case (Some(_), Some(key)) =>
          logging.info(this, s"Remove init revision map cause queue is removed, key: ${key}")
          initRevisionMap.remove(key)
        case _ => // do nothing
      }
      sender ! QueueRemovedCompleted // notify queue that it can stop safely

    // a Removed queue backed to Running
    case QueueReactivated(invocationNamespace, action, docInfo) =>
      QueuePool.put(MemoryQueueKey(invocationNamespace, docInfo), MemoryQueueValue(sender(), true))
      updateInitRevisionMap(getLeaderKey(invocationNamespace, action), docInfo.rev)

    // only handle prefix watcher
    case WatchEndpointInserted(_, key, value, true) =>
      if (key.contains("leader") && value.contains("host")) {
        SchedulerEndpoints
          .parse(value)
          .map { endpoints =>
            logging.info(this, s"Endpoint inserted, key: $key, endpoints: $endpoints")
            actorSelectionMap.update(key, endpoints.getRemoteRef(QueueManager.actorName))
          }
          .recover {
            case t =>
              logging.error(this, s"Unexpected error $t when put leaderKey: ${key}")
          }
      }

    // only handle prefix watcher
    case WatchEndpointRemoved(_, key, _, true) =>
      if (key.contains("leader")) {
        if (actorSelectionMap.contains(key)) {
          logging.info(this, s"Endpoint removed for key: $key")
          actorSelectionMap.remove(key)
        } else {
          logging.info(this, s"Endpoint removed for key: $key but not in this scheduler")
        }
      }

    case GracefulShutdown =>
      logging.info(this, s"Gracefully shutdown the queue manager")

      watcherService ! UnwatchEndpoint(QueueKeys.queuePrefix, isPrefix = true, watcherName)
      logScheduler.cancel()
      healthReporter ! PoisonPill
      dataManagementService ! UnregisterData(SchedulerKeys.scheduler(schedulerId))

      QueuePool.values.foreach { queueInfo =>
        //send GracefulShutdown as the queue is not outdated
        queueInfo.queue ! GracefulShutdown
      }

      // this is for graceful shutdown of the feed as well.
      // When the scheduler endpoint is removed, there can be some unprocessed data in Kafka
      // So we would wait for some time to consume all messages in Kafka
      akka.pattern.after(5.seconds, system.scheduler) {
        feed ! GracefulShutdown
        Future.successful({})
      }

    case QueueSize =>
      sender ! QueuePool.size

    case StatusQuery =>
      val poolStatus = Future.sequence {
        QueuePool.values.map(_.queue.ask(StatusQuery).mapTo[StatusData])
      }
      sender ! poolStatus

    case msg =>
      logging.error(this, s"failed to elect a leader for ${msg}")

  }

  private def handler(bytes: Array[Byte]): Future[Unit] = {
    Future(
      ActivationMessage
        .parse(new String(bytes, StandardCharsets.UTF_8)))
      .flatMap(Future.fromTry)
      .flatMap { msg =>
        if (isWarmUpAction(msg.action)) {
          logging.info(
            this,
            s"The ${msg.action} action is an action used to connect a network level connection. So drop the message without executing activation")
        } else {
          logging.info(
            this,
            s"Got activation message ${msg.activationId} for ${msg.user.namespace}/${msg.action} from kafka.")(
            msg.transid)
          handleActivationMessage(msg)
        }
        feed ! MessageFeed.Processed
        Future.successful({})
      }
      .recover {
        case t: DeserializationException =>
          feed ! MessageFeed.Processed
          logging.warn(this, s"Failed to parse message to ActivationMessage, ${t.getMessage}")
      }
  }

  private val feed = system.actorOf(Props {
    new MessageFeed("activation", logging, schedulerConsumer, maxPeek, 1.second, handler)
  })

  private def updateInitRevisionMap(key: String, revision: DocRevision): Unit = {
    logging.info(this, s"Update init revision map, key: ${key}, rev: ${revision.rev}")
    initRevisionMap.update(key, revision)
  }

  private def createNewQueue(newAction: FullyQualifiedEntityName, msg: ActivationMessage)(
    implicit transid: TransactionId): Future[Any] = {
    val start = transid.started(this, LoggingMarkers.SCHEDULER_QUEUE_UPDATE("version-mismatch"))

    logging.info(this, s"Create a new queue for ${newAction}, rev: ${msg.revision}")

    getWhiskActionMetaData(entityStore, newAction.toDocId, msg.revision, msg.revision != DocRevision.empty)
      .map { actionMetaData: WhiskActionMetaData =>
        actionMetaData.toExecutableWhiskAction match {
          // Always use revision got from Database, there can be 2 cases for the actionMetaData.rev
          // 1. msg.revision == actionMetaData.rev => OK
          // 2. msg.revision != actionMetaData.rev => the msg.revision must be empty, else an mismatch error will be
          //                                          threw, we can use the revision got from Database
          case Some(_) =>
            self ! CreateNewQueue(
              msg.copy(revision = actionMetaData.rev, action = msg.action.copy(version = Some(actionMetaData.version))),
              newAction.copy(version = Some(actionMetaData.version)),
              actionMetaData)
            transid.finished(this, start, s"action is updated to ${newAction.toDocId.asDocInfo(actionMetaData.rev)}")

          case None =>
            val message = s"non-executable action: ${newAction} with rev: ${msg.revision} reached queueManager"
            transid.failed(this, start, message)
            completeErrorActivation(msg, message)
        }
      }
      .recoverWith {
        case DocumentRevisionMismatchException(_) =>
          logging.warn(this, s"Document revision is mismatched for ${newAction}, rev: ${msg.revision}")
          createNewQueue(newAction, msg.copy(revision = DocRevision.empty))
        case t =>
          transid.failed(
            this,
            start,
            s"failed to fetch action $newAction with rev: ${msg.revision}, error ${t.getMessage}")
          completeErrorActivation(msg, t.getMessage)
      }
  }

  private def handleActivationMessage(msg: ActivationMessage): Any = {
    implicit val transid = msg.transid

    // Drop the message that has not been scheduled for a long time
    val schedulingWaitTime = Interval(msg.transid.meta.start, Instant.now()).duration
    MetricEmitter.emitHistogramMetric(LoggingMarkers.SCHEDULER_WAIT_TIME, schedulingWaitTime.toMillis)

    if (schedulingWaitTime > queueManagerConfig.maxSchedulingTime) {
      logging.warn(
        this,
        s"[${msg.activationId}] the activation message has not been scheduled for ${queueManagerConfig.maxSchedulingTime.toSeconds} sec")
      completeErrorActivation(msg, "The activation has not been processed")
    } else {
      QueuePool.get(MemoryQueueKey(msg.user.namespace.name.asString, msg.action.toDocId.asDocInfo(msg.revision))) match {
        case Some(memoryQueueValue) if memoryQueueValue.isLeader =>
          memoryQueueValue.queue ! msg
        case _ =>
          val key = QueueKeys.queue(msg.user.namespace.name.asString, msg.action.copy(version = None), true)

          initRevisionMap.get(key) match {
            case Some(revision) =>
              if (msg.revision > revision) {
                logging.warn(
                  this,
                  s"[${msg.activationId}] the action version is not matched for ${msg.action.path}/${msg.action.name}, current: ${revision}, received: ${msg.revision}")
                MetricEmitter.emitCounterMetric(LoggingMarkers.SCHEDULER_QUEUE_UPDATE("version-mismatch"))
                val newAction = msg.action.copy(binding = None)

                self ! UpdateMemoryQueue(msg.action.toDocId.asDocInfo(revision), newAction, msg)
              } else if (msg.revision < revision) {
                // if revision is mismatched, the action may have been updated,
                // so try again with the latest code
                logging.warn(
                  this,
                  s"[${msg.activationId}] activation message with an old revision arrived, it will be replaced with the latest revision and invoked, current: ${revision}, received: ${msg.revision}")
                sendActivationByLeaderKey(key, msg.copy(revision = revision))
              } else {
                // The code will not run here under normal cases. it's for insurance
                logging.warn(
                  this,
                  s"[${msg.activationId}] The code will not run here under normal cases, rev: ${msg.revision}")
                sendActivationByLeaderKey(key, msg)
              }
            case None =>
              logging.info(
                this,
                s"[${msg.activationId}] the key ${key} is not in the initRevisionMap. revision: ${msg.revision}")
              sendActivationByLeaderKey(key, msg)
          }
      }
    }
  }

  private def sendActivationByLeaderKey(key: String, msg: ActivationMessage)(implicit transid: TransactionId) = {
    actorSelectionMap.get(key) match {
      case Some(actorSelect) =>
        actorSelect ! msg
      case None =>
        sendActivationToRemoteQueue(key, msg)
    }
  }

  private def sendActivationToRemoteQueue(key: String, msg: ActivationMessage)(
    implicit transid: TransactionId): Future[Any] = {
    logging.info(this, s"[${msg.activationId}] send activation to remote queue, key: ${key} revision: ${msg.revision}")

    getQueueEndpoint(key) map { endPoint =>
      Future(
        SchedulerEndpoints
          .parse(endPoint))
        .flatMap(Future.fromTry)
        .map(endPoint => {
          val actorSelection = endPoint.getRemoteRef(QueueManager.actorName)
          logging.info(this, s"add a new actor selection to a map with key: $key")
          actorSelectionMap.update(key, actorSelection)
          actorSelection ! msg
        })
        .recoverWith {
          case t =>
            logging.warn(this, s"[${msg.activationId}] failed to parse endpoints (${t.getMessage})")
            completeErrorActivation(msg, "The activation has not been processed")
        }

    } recoverWith {
      case t =>
        logging.warn(this, s"[${msg.activationId}] activation has been dropped (${t.getMessage})")
        completeErrorActivation(msg, "The activation has not been processed")
    }
  }

  private def getQueueEndpoint(key: String) = {
    retryFuture(maxRetries = queueManagerConfig.maxRetriesToGetQueue) {
      etcdClient.get(key).map { res =>
        res.getKvsList.asScala.headOption match {
          case Some(kv) => kv.getValue.toStringUtf8
          case None     => throw new Exception(s"Failed to get endpoint ($key)")
        }
      }
    }
  }

  private def retryFuture[T](maxRetries: Int = 13,
                             retries: Int = 1,
                             factor: Float = 2.0f,
                             initWait: Int = 1,
                             curWait: Int = 0)(fn: => Future[T]): Future[T] = {
    fn recoverWith {
      case e if retries <= maxRetries =>
        val wait =
          if (curWait == 0) initWait
          else Math.ceil(curWait * factor).toInt
        akka.pattern.after(wait.milliseconds, system.scheduler) {
          val message = s"${e.getMessage} retrying after ${wait}ms ($retries/$maxRetries)"
          if (retries == maxRetries) {
            // if number of retries reaches maxRetries, print warning level log
            logging.warn(this, message)
          } else {
            logging.info(this, message)
          }
          retryFuture(maxRetries, retries + 1, factor, initWait, wait)(fn)
        }
    }
  }

  private def electLeaderAndCreateQueue(request: CreateQueue, receiver: Option[ActorRef] = None) = {
    request.whiskActionMetaData.toExecutableWhiskAction match {
      case Some(_) =>
        val leaderKey = getLeaderKey(request)

        // callback will be executed after leader election
        leaderElectionCallbacks.get(leaderKey) match {
          case None =>
            dataManagementService ! ElectLeader(leaderKey, schedulerEndpoints.serialize, self)
            leaderElectionCallbacks.put(
              leaderKey, {
                case Right(EtcdLeader(_, _, _)) =>
                  val queue = childFactory(
                    context,
                    request.invocationNamespace,
                    request.fqn,
                    request.revision,
                    request.whiskActionMetaData)
                  queue ! Start
                  QueuePool.put(
                    MemoryQueueKey(request.invocationNamespace, request.fqn.toDocId.asDocInfo(request.revision)),
                    MemoryQueueValue(queue, true))
                  updateInitRevisionMap(leaderKey, request.revision)
                  receiver.foreach(_ ! CreateQueueResponse(request.invocationNamespace, request.fqn, success = true))

                // in case of follower, do nothing
                case Left(EtcdFollower(_, _)) =>
                  receiver.foreach(_ ! CreateQueueResponse(request.invocationNamespace, request.fqn, success = true))
              })

          // there is already a leader election for leaderKey, so skip it
          case Some(_) =>
            receiver foreach (_ ! CreateQueueResponse(request.invocationNamespace, request.fqn, success = true))
        }

      case None =>
        logging.error(this, s"non-executable action: ${request.fqn} with rev: ${request.revision} reached QueueManager")
        receiver match {
          case Some(recipient) =>
            recipient ! CreateQueueResponse(request.invocationNamespace, request.fqn, success = false)
          case None =>
          // do nothing
        }

    }
  }

  private val logScheduler = context.system.scheduler.scheduleAtFixedRate(0.seconds, 1.seconds)(() => {
    MetricEmitter.emitHistogramMetric(LoggingMarkers.SCHEDULER_QUEUE, QueuePool.countLeader())
  })

  private val healthReporter = Scheduler.scheduleWaitAtLeast(1.seconds, 1.seconds) { () =>
    val leaderCount = QueuePool.countLeader()
    dataManagementService ! UpdateDataOnChange(
      SchedulerKeys.scheduler(schedulerId),
      SchedulerStates(schedulerId, leaderCount, schedulerEndpoints).serialize)
    Future.successful({})
  }

  private def completeErrorActivation(activation: ActivationMessage, message: String): Future[Any] = {
    val activationResponse =
      generateFallbackActivation(activation, OriginActivationResponse.whiskError(message))

    val ackMsg = if (activation.blocking) {
      CombinedCompletionAndResultMessage(activation.transid, activationResponse, schedulerId)
    } else {
      CompletionMessage(activation.transid, activationResponse, schedulerId)
    }

    ack(
      activation.transid,
      activationResponse,
      activation.blocking,
      activation.rootControllerIndex,
      activation.user.namespace.uuid,
      ackMsg)
      .andThen {
        case Failure(t) =>
          logging.error(this, s"failed to send ack due to ${t}")
      }
    store(activation.transid, activationResponse, UserContext(activation.user))
  }

  /** Generates an activation with zero runtime. Usually used for error cases */
  private def generateFallbackActivation(msg: ActivationMessage,
                                         response: OriginActivationResponse): WhiskActivation = {
    val now = Instant.now
    val causedBy = if (msg.causedBySequence) {
      Some(Parameters(WhiskActivation.causedByAnnotation, JsString(Exec.SEQUENCE)))
    } else None

    val binding =
      msg.action.binding.map(f => Parameters(WhiskActivation.bindingAnnotation, JsString(f.asString)))

    WhiskActivation(
      activationId = msg.activationId,
      namespace = msg.user.namespace.name.toPath,
      subject = msg.user.subject,
      cause = msg.cause,
      name = msg.action.name,
      version = msg.action.version.getOrElse(SemVer()),
      start = now,
      end = now,
      duration = Some(0),
      response = response,
      annotations = {
        Parameters(WhiskActivation.pathAnnotation, JsString(msg.action.copy(version = None).asString)) ++
          Parameters(WhiskActivation.kindAnnotation, JsString(Exec.UNKNOWN)) ++ causedBy ++ binding
      })
  }

  private def getLeaderKey(request: CreateQueue) = {
    QueueKeys.queue(request.invocationNamespace, request.fqn.copy(version = None), leader = true)
  }

  private def getLeaderKey(invocationNamespace: String, fqn: FullyQualifiedEntityName) = {
    QueueKeys.queue(invocationNamespace, fqn.copy(version = None), leader = true)
  }
}

object QueueManager {
  val actorName = "QueueManager"

  def props(
    entityStore: ArtifactStore[WhiskEntity],
    getWhiskActionMetaData: (ArtifactStore[WhiskEntity], DocId, DocRevision, Boolean) => Future[WhiskActionMetaData],
    etcdClient: EtcdClient,
    schedulerEndpoints: SchedulerEndpoints,
    schedulerId: SchedulerInstanceId,
    dataManagementService: ActorRef,
    watcherService: ActorRef,
    ack: ActiveAck,
    store: (TransactionId, WhiskActivation, UserContext) => Future[Any],
    childFactory: (ActorRefFactory, String, FullyQualifiedEntityName, DocRevision, WhiskActionMetaData) => ActorRef,
    schedulerConsumer: MessageConsumer)(implicit logging: Logging): Props = {
    Props(
      new QueueManager(
        entityStore,
        getWhiskActionMetaData,
        etcdClient,
        schedulerEndpoints,
        schedulerId,
        dataManagementService,
        watcherService,
        ack,
        store,
        childFactory,
        schedulerConsumer))
  }
}

sealed trait MemoryQueueError extends Product {
  val causedBy: String
}

object MemoryQueueErrorSerdes {

  private implicit val noMessageSerdes = NoActivationMessage.serdes
  private implicit val noQueueSerdes = NoMemoryQueue.serdes
  private implicit val mismatchSerdes = ActionMismatch.serdes

  // format that discriminates based on an additional
  // field "type" that can either be "Cat" or "Dog"
  implicit val memoryQueueErrorFormat = new RootJsonFormat[MemoryQueueError] {
    def write(obj: MemoryQueueError): JsValue =
      JsObject((obj match {
        case msg: NoActivationMessage => msg.toJson
        case msg: NoMemoryQueue       => msg.toJson
        case msg: ActionMismatch      => msg.toJson
      }).asJsObject.fields + ("type" -> JsString(obj.productPrefix)))

    def read(json: JsValue): MemoryQueueError =
      json.asJsObject.getFields("type") match {
        case Seq(JsString("NoActivationMessage")) => json.convertTo[NoActivationMessage]
        case Seq(JsString("NoMemoryQueue"))       => json.convertTo[NoMemoryQueue]
        case Seq(JsString("ActionMismatch"))      => json.convertTo[ActionMismatch]
      }
  }
}

case class NoActivationMessage(noActivationMessage: String = NoActivationMessage.asString)
    extends MemoryQueueError
    with Message {
  override val causedBy: String = noActivationMessage
  override def serialize = NoActivationMessage.serdes.write(this).compactPrint
}

object NoActivationMessage extends DefaultJsonProtocol {
  val asString: String = "no activation message exist"
  def parse(msg: String) = Try(serdes.read(msg.parseJson))
  implicit val serdes = jsonFormat(NoActivationMessage.apply _, "noActivationMessage")
}

case class NoMemoryQueue(noMemoryQueue: String = NoMemoryQueue.asString) extends MemoryQueueError with Message {
  override val causedBy: String = noMemoryQueue
  override def serialize = NoMemoryQueue.serdes.write(this).compactPrint
}

object NoMemoryQueue extends DefaultJsonProtocol {
  val asString: String = "no memory queue exist"
  def parse(msg: String) = Try(serdes.read(msg.parseJson))
  implicit val serdes = jsonFormat(NoMemoryQueue.apply _, "noMemoryQueue")
}

case class ActionMismatch(actionMisMatch: String = ActionMismatch.asString) extends MemoryQueueError with Message {
  override val causedBy: String = actionMisMatch
  override def serialize = ActionMismatch.serdes.write(this).compactPrint
}

object ActionMismatch extends DefaultJsonProtocol {
  val asString: String = "action version does not match"
  def parse(msg: String) = Try(serdes.read(msg.parseJson))
  implicit val serdes = jsonFormat(ActionMismatch.apply _, "actionMisMatch")
}

object QueuePool {
  private val _queuePool = TrieMap[MemoryQueueKey, MemoryQueueValue]()

  private[scheduler] def get(key: MemoryQueueKey) = _queuePool.get(key)

  private[scheduler] def put(key: MemoryQueueKey, value: MemoryQueueValue) = _queuePool.put(key, value)

  private[scheduler] def remove(key: MemoryQueueKey) = _queuePool.remove(key)

  private[scheduler] def countLeader() = _queuePool.count(_._2.isLeader)

  private[scheduler] def clear(): Unit = _queuePool.clear()

  private[scheduler] def size = _queuePool.size

  private[scheduler] def values = _queuePool.values

  private[scheduler] def keys = _queuePool.keys
}

case class CreateQueue(invocationNamespace: String,
                       fqn: FullyQualifiedEntityName,
                       revision: DocRevision,
                       whiskActionMetaData: WhiskActionMetaData)
case class CreateQueueResponse(invocationNamespace: String, fqn: FullyQualifiedEntityName, success: Boolean)

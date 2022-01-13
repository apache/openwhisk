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

import java.time.{Duration, Instant}
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.Status.{Failure => FailureMessage}
import akka.actor.{ActorRef, ActorSystem, Cancellable, FSM, Props, Stash}
import akka.util.Timeout
import org.apache.openwhisk.common._
import org.apache.openwhisk.core.ack.ActiveAck
import org.apache.openwhisk.core.connector.ContainerCreationError.{TooManyConcurrentRequests, ZeroNamespaceLimit}
import org.apache.openwhisk.core.connector._
import org.apache.openwhisk.core.database.{NoDocumentException, UserContext}
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.etcd.EtcdClient
import org.apache.openwhisk.core.etcd.EtcdKV.ContainerKeys.containerPrefix
import org.apache.openwhisk.core.etcd.EtcdKV.{ContainerKeys, QueueKeys, ThrottlingKeys}
import org.apache.openwhisk.core.scheduler.SchedulerEndpoints
import org.apache.openwhisk.core.scheduler.message.{
  ContainerCreation,
  ContainerDeletion,
  FailedCreationJob,
  SuccessfulCreationJob
}
import org.apache.openwhisk.core.scheduler.grpc.{GetActivation, ActivationResponse => GetActivationResponse}
import org.apache.openwhisk.core.scheduler.message.{
  ContainerCreation,
  ContainerDeletion,
  FailedCreationJob,
  SuccessfulCreationJob
}
import org.apache.openwhisk.core.service._
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import org.apache.openwhisk.http.Messages.{namespaceLimitUnderZero, tooManyConcurrentRequests}
import pureconfig.generic.auto._
import pureconfig.loadConfigOrThrow
import spray.json._

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{duration, ExecutionContextExecutor, Future, Promise}
import scala.language.postfixOps
import scala.util.{Failure, Success}

// States
sealed trait MemoryQueueState
case object Uninitialized extends MemoryQueueState
case object Running extends MemoryQueueState
case object Idle extends MemoryQueueState
case object Flushing extends MemoryQueueState
case object Removing extends MemoryQueueState
case object Removed extends MemoryQueueState
case object ActionThrottled extends MemoryQueueState
case object NamespaceThrottled extends MemoryQueueState

// Data
sealed abstract class MemoryQueueData()
case class NoData() extends MemoryQueueData()
case class NoActors() extends MemoryQueueData()
case class RunningData(schedulerActor: ActorRef, droppingActor: ActorRef) extends MemoryQueueData()
case class ThrottledData(schedulerActor: ActorRef, droppingActor: ActorRef) extends MemoryQueueData()
case class FlushingData(schedulerActor: ActorRef,
                        droppingActor: ActorRef,
                        error: ContainerCreationError,
                        reason: String,
                        activeDuringFlush: Boolean = false)
    extends MemoryQueueData()
case class RemovingData(schedulerActor: ActorRef, droppingActor: ActorRef, outdated: Boolean) extends MemoryQueueData()

// Events sent by the actor
case class QueueRemoved(invocationNamespace: String, action: DocInfo, leaderKey: Option[String])
case class QueueReactivated(invocationNamespace: String, action: FullyQualifiedEntityName, docInfo: DocInfo)
case class CancelPoll(promise: Promise[Either[MemoryQueueError, ActivationMessage]])
case object QueueRemovedCompleted
case object FlushPulse

// Events received by the actor
case object Start
case object VersionUpdated
case object StopSchedulingAsOutdated

sealed trait RequiredAction
case object Skip extends RequiredAction
case object AddInitialContainer extends RequiredAction
case object AddContainer extends RequiredAction
case class EnableNamespaceThrottling(dropMsg: Boolean) extends RequiredAction
case object DisableNamespaceThrottling extends RequiredAction
case object EnableActionThrottling extends RequiredAction
case object DisableActionThrottling extends RequiredAction
case object Pausing extends RequiredAction
case class DecisionResults(required: RequiredAction, num: Int)

case class TimeSeriesActivationEntry(timestamp: Instant, msg: ActivationMessage)

class MemoryQueue(private val etcdClient: EtcdClient,
                  private val durationChecker: DurationChecker,
                  private val action: FullyQualifiedEntityName,
                  messagingProducer: MessageProducer,
                  config: WhiskConfig,
                  invocationNamespace: String,
                  revision: DocRevision,
                  endpoints: SchedulerEndpoints,
                  actionMetaData: WhiskActionMetaData,
                  dataManagementService: ActorRef,
                  watcherService: ActorRef,
                  containerManager: ActorRef,
                  decisionMaker: ActorRef,
                  schedulerId: SchedulerInstanceId,
                  ack: ActiveAck,
                  store: (TransactionId, WhiskActivation, UserContext) => Future[Any],
                  getUserLimit: String => Future[Int],
                  checkToDropStaleActivation: (Queue[TimeSeriesActivationEntry],
                                               Long,
                                               String,
                                               FullyQualifiedEntityName,
                                               MemoryQueueState,
                                               ActorRef) => Unit,
                  queueConfig: QueueConfig)(implicit logging: Logging)
    extends FSM[MemoryQueueState, MemoryQueueData]
    with Stash {

  private implicit val ec: ExecutionContextExecutor = context.dispatcher
  private implicit val actorSystem: ActorSystem = context.system
  private implicit val timeout = Timeout(5.seconds)
  private implicit val order: Ordering[BufferedRequest] = Ordering.by(_.containerId)

  private val unversionedAction = action.copy(version = None)
  private val checkInterval: FiniteDuration = 100 milliseconds
  private val StaleThreshold: Double = 100.0
  private val StaleDuration = Duration.ofMillis(StaleThreshold.toLong)
  private val dropInterval: FiniteDuration = 10 seconds
  private val leaderKey = QueueKeys.queue(invocationNamespace, unversionedAction, leader = true)
  private val inProgressContainerPrefixKey =
    containerPrefix(ContainerKeys.inProgressPrefix, invocationNamespace, action, Some(revision))
  private val existingContainerPrefixKey =
    containerPrefix(ContainerKeys.namespacePrefix, invocationNamespace, action, Some(revision))
  private val namespaceThrottlingKey = ThrottlingKeys.namespace(EntityName(invocationNamespace))
  private val actionThrottlingKey = ThrottlingKeys.action(invocationNamespace, unversionedAction)
  private val pollTimeOut = 1.seconds
  private var requestBuffer = mutable.PriorityQueue.empty[BufferedRequest]
  private val memory = actionMetaData.limits.memory.megabytes.MB
  private val queueRemovedMsg = QueueRemoved(invocationNamespace, action.toDocId.asDocInfo(revision), Some(leaderKey))
  private val staleQueueRemovedMsg = QueueRemoved(invocationNamespace, action.toDocId.asDocInfo(revision), None)

  private[queue] var containers = Set.empty[String]
  private[queue] var creationIds = Set.empty[String]

  private[queue] var queue = Queue.empty[TimeSeriesActivationEntry]
  private[queue] var in = new AtomicInteger(0)
  private[queue] val namespaceContainerCount = NamespaceContainerCount(invocationNamespace, etcdClient, watcherService)
  private[queue] var averageDuration: Option[Double] = None
  private[queue] var averageDurationBuffer = AverageRingBuffer(queueConfig.durationBufferSize)
  private[queue] var limit: Option[Int] = None
  private[queue] var initialized = false

  private val logScheduler: Cancellable = context.system.scheduler.scheduleWithFixedDelay(0.seconds, 1.seconds) { () =>
    MetricEmitter.emitGaugeMetric(
      LoggingMarkers.SCHEDULER_QUEUE_WAITING_ACTIVATION(s"$invocationNamespace/$action"),
      queue.size)

    MetricEmitter.emitGaugeMetric(
      LoggingMarkers.SCHEDULER_NAMESPACE_CONTAINER(invocationNamespace),
      namespaceContainerCount.existingContainerNumByNamespace)
    MetricEmitter.emitGaugeMetric(
      LoggingMarkers.SCHEDULER_NAMESPACE_INPROGRESS_CONTAINER(invocationNamespace),
      namespaceContainerCount.inProgressContainerNumByNamespace)

    MetricEmitter.emitGaugeMetric(
      LoggingMarkers.SCHEDULER_ACTION_CONTAINER(invocationNamespace, action.asString),
      containers.size)
    MetricEmitter.emitGaugeMetric(
      LoggingMarkers.SCHEDULER_ACTION_INPROGRESS_CONTAINER(invocationNamespace, action.asString),
      creationIds.size)
  }

  getAverageDuration()

  private val watcherName = s"memory-queue-$action-$revision"
  // watch existing containers for action and namespace
  private val watchedKeys = Seq(inProgressContainerPrefixKey, existingContainerPrefixKey)

  watchedKeys.foreach { key =>
    watcherService ! WatchEndpoint(key, "", isPrefix = true, watcherName, Set(PutEvent, DeleteEvent))
  }

  startWith(Uninitialized, NoData())

  when(Uninitialized) {
    case Event(Start, _) =>
      logging.info(this, s"[$invocationNamespace:$action:$stateName] a new queue is created.")
      val (schedulerActor, droppingActor) = startMonitoring()
      initializeThrottling()

      watcherService ! WatchEndpoint(leaderKey, endpoints.serialize, isPrefix = false, watcherName, Set(DeleteEvent))

      goto(Running) using RunningData(schedulerActor, droppingActor)

    // this is the case that the action version is updated, so no data needs to be stored
    case Event(VersionUpdated, _) =>
      val (schedulerActor, droppingActor) = startMonitoring()

      goto(Running) using RunningData(schedulerActor, droppingActor)

    // other messages should not be handled in this state.
    case _ =>
      stash()
      stay
  }

  when(Running, stateTimeout = queueConfig.idleGrace) {
    case Event(EnableNamespaceThrottling(dropMsg), data: RunningData) =>
      logging.info(this, s"[$invocationNamespace:$action:$stateName] Enable namespace throttling.")
      enableNamespaceThrottling()

      // if no container could be created, it is same with Flushing state.
      if (dropMsg) {
        completeAllActivations(tooManyConcurrentRequests, isWhiskError = false)
        goto(Flushing) using FlushingData(
          data.schedulerActor,
          data.droppingActor,
          TooManyConcurrentRequests,
          tooManyConcurrentRequests)
      } else {
        // if there are already some containers running, activations can still be processed so goto the NamespaceThrottled state.
        goto(NamespaceThrottled) using ThrottledData(data.schedulerActor, data.droppingActor)
      }

    case Event(StateTimeout, data: RunningData) =>
      if (queue.isEmpty && (containers.size + creationIds.size) <= 0) {
        logging.info(
          this,
          s"[$invocationNamespace:$action:$stateName] No activations coming in ${queueConfig.idleGrace}")
        actorSystem.stop(data.schedulerActor)
        actorSystem.stop(data.droppingActor)

        goto(Idle) using NoActors()
      } else {
        logging.info(
          this,
          s"[$invocationNamespace:$action:$stateName] The queue is timed out but there are still ${queue.size} activation messages or (running: ${containers.size}, in-progress: ${creationIds.size}) containers")
        stay
      }

    case Event(FailedCreationJob(creationId, _, _, _, error, message), RunningData(schedulerActor, droppingActor)) =>
      creationIds -= creationId.asString
      // when there is no container, it moves to the Flushing state as no activations can be invoked
      if (containers.size <= 0) {
        val isWhiskError = ContainerCreationError.whiskErrors.contains(error)
        completeAllActivations(message, isWhiskError)
        logging.error(
          this,
          s"[$invocationNamespace:$action:$stateName] Failed to create an initial container due to ${if (isWhiskError) "whiskError"
          else "developerError"}, reason: $message.")

        goto(Flushing) using FlushingData(schedulerActor, droppingActor, error, message)
      } else
        // if there are already some containers running, activations can be handled anyway.
        stay
  }

  // there is no timeout for this state as when there is no further message, it would move to the Running state again.
  when(NamespaceThrottled) {
    case Event(msg: ActivationMessage, _: ThrottledData) =>
      handleActivationMessage(msg)
      stay

    case Event(DisableNamespaceThrottling, data: ThrottledData) =>
      logging.info(this, s"[$invocationNamespace:$action:$stateName] Disable namespace throttling.")
      disableNamespaceThrottling()
      goto(Running) using RunningData(data.schedulerActor, data.schedulerActor)
  }

  // there is no timeout for this state as when there is no further message, it would move to the Running state again.
  when(ActionThrottled) {
    // since there are already too many activation messages, it drops the new messages
    case Event(msg: ActivationMessage, ThrottledData(_, _)) =>
      completeErrorActivation(msg, tooManyConcurrentRequests, isWhiskError = false)
      stay
  }

  when(Idle, stateTimeout = queueConfig.stopGrace) {
    case Event(msg: ActivationMessage, _: NoActors) =>
      val (schedulerActor, droppingActor) = startMonitoring()
      handleActivationMessage(msg)
      goto(Running) using RunningData(schedulerActor, droppingActor)

    case Event(request: GetActivation, _) if request.action == action =>
      sender ! GetActivationResponse(Left(NoActivationMessage()))
      stay

    case Event(StateTimeout, _: NoActors) =>
      logging.info(this, s"[$invocationNamespace:$action:$stateName] The queue is timed out, stop the queue.")
      cleanUpDataAndGotoRemoved()

    case Event(GracefulShutdown, _: NoActors) =>
      logging.info(this, s"[$invocationNamespace:$action:$stateName] Received GracefulShutdown, stop the queue.")
      cleanUpDataAndGotoRemoved()

    case Event(StopSchedulingAsOutdated, _: NoActors) =>
      logging.info(this, s"[$invocationNamespace:$action:$stateName] stop further scheduling.")

      cleanUpWatcher()

      // let QueueManager know this queue is no longer in charge.
      context.parent ! staleQueueRemovedMsg

      // since the queue is outdated and there is no activation, delete all old containers.
      containerManager ! ContainerDeletion(invocationNamespace, action, revision, actionMetaData)

      goto(Removed) using NoData()
  }

  when(Flushing) {
    // an initial container is successfully created.
    case Event(SuccessfulCreationJob(creationId, _, _, _), FlushingData(schedulerActor, droppingActor, _, _, _)) =>
      creationIds -= creationId.asString

      goto(Running) using RunningData(schedulerActor, droppingActor)

    // log the failed information
    case Event(FailedCreationJob(creationId, _, _, _, _, message), data: FlushingData) =>
      creationIds -= creationId.asString
      logging.info(
        this,
        s"[$invocationNamespace:$action:$stateName][$creationId] Failed to create a container due to $message")

      // keep updating the reason
      stay using data.copy(reason = message)

    // since there is no container, activations cannot be handled.
    case Event(msg: ActivationMessage, data: FlushingData) =>
      completeErrorActivation(msg, data.reason, ContainerCreationError.whiskErrors.contains(data.error))
      stay() using data.copy(activeDuringFlush = true)

    // Since SchedulingDecisionMaker keep sending a message to create a container, this state is not automatically timed out.
    // Instead, StateTimeout message will be sent by a timer.
    case Event(StateTimeout, data: FlushingData) =>
      completeAllActivations(data.reason, ContainerCreationError.whiskErrors.contains(data.error))
      if (data.activeDuringFlush)
        stay using data.copy(activeDuringFlush = false)
      else
        cleanUpActorsAndGotoRemoved(data)

    case Event(GracefulShutdown, data: FlushingData) =>
      completeAllActivations(data.reason, ContainerCreationError.whiskErrors.contains(data.error))
      logging.info(this, s"[$invocationNamespace:$action:$stateName] Received GracefulShutdown, stop the queue.")
      cleanUpActorsAndGotoRemoved(data)
  }

  // in case there is any activation in the queue, it waits until all of them are handled.
  when(Removing, stateTimeout = queueConfig.gracefulShutdownTimeout) {
    // When there is no message in the queue, SchedulingDecisionMaker would stop sending any message
    // So the queue can be timed out on every gracefulShutdownTimeout
    case Event(QueueRemovedCompleted | StateTimeout, data: RemovingData) =>
      cleanUpActorsAndGotoRemovedIfPossible(data)

    case Event(GracefulShutdown, data: RemovingData) =>
      logging.info(
        this,
        s"[$invocationNamespace:$action:$stateName] The queue received GracefulShutdown trying to stop the queue.")
      cleanUpActorsAndGotoRemovedIfPossible(data)

    case Event(StopSchedulingAsOutdated, data: RemovingData) =>
      logging.info(
        this,
        s"[$invocationNamespace:$action:$stateName] The queue received StopSchedulingAsOutdated trying to stop the queue.")
      cleanUpActorsAndGotoRemovedIfPossible(data.copy(outdated = true))
  }

  when(Removed, stateTimeout = queueConfig.gracefulShutdownTimeout) {
    // since this Queue will be terminated, rescheduling the msg
    case Event(msg: ActivationMessage, _: NoData) =>
      context.parent ! msg
      stay()

    // this queue is going to stop so let client connect to a new queue
    case Event(request: GetActivation, _: NoData) if request.action == action =>
      implicit val tid = request.transactionId
      logging.info(
        this,
        s"[$invocationNamespace:$action:$stateName] Get activation request ${request.containerId}, let client connect to a new queue.")
      forwardAllActivations(context.parent)
      sender ! GetActivationResponse(Left(NoMemoryQueue()))

      stay

    // actors and data are already wiped
    case Event(QueueRemovedCompleted, _: NoData) =>
      stop()

    // This is not supposed to happen. This will ensure the queue does not run forever.
    // This can happen when QueueManager could not respond with QueueRemovedCompleted for some reason.
    case Event(StateTimeout, _: NoData) =>
      context.parent ! queueRemovedMsg

      stop()

    // This queue is going to stop, do nothing
    case Event(msg @ (StopSchedulingAsOutdated | GracefulShutdown), _: NoData) =>
      logging.info(
        this,
        s"[$invocationNamespace:$action:$stateName] The queue received $msg but do nothing as it is going to stop.")
      stay
  }

  whenUnhandled {
    // The queue endpoint is removed, trying to restore it.
    case Event(WatchEndpointRemoved(_, `leaderKey`, value, false), data) =>
      data match {
        case RemovingData(_, _, _) =>
          logging.info(
            this,
            s"[$invocationNamespace:$action:$stateName] This queue is shutdown by `/disable` api, do nothing here.")
        case _ =>
          dataManagementService ! RegisterInitialData(leaderKey, value, failoverEnabled = false, Some(self)) // the watcher is already setup
      }
      stay

    // we don't care the storage results for namespaceThrottlingKey
    case Event(InitialDataStorageResults(`namespaceThrottlingKey`, _), _) =>
      stay

    // The queue endpoint is restored
    case Event(InitialDataStorageResults(`leaderKey`, Right(_)), _) =>
      stay

    // this can be a case that there is another queue already running.
    // it can happen if a node is segregated by the temporal network rupture and the queue endpoint is removed.
    case Event(InitialDataStorageResults(`leaderKey`, Left(_)), data) =>
      logging.warn(this, s"[$invocationNamespace:$action:$stateName] the queue is superseded by a new queue.")
      // let QueueManager know this queue is no longer in charge.
      context.parent ! queueRemovedMsg

      // forward all activations to the parent queue manager.
      // parent queue manager is supposed to removed the reference of this queue and forward messages to a new queue
      forwardAllActivations(context.parent)

      // only clean up actors because etcd data is already being used by another queue
      cleanUpActors(data)

      goto(Removed) using NoData()

    case Event(WatchEndpointRemoved(watchKey, key, _, true), _) =>
      watchKey match {
        case `inProgressContainerPrefixKey` =>
          creationIds -= key.split("/").last
        case `existingContainerPrefixKey` =>
          containers -= key.split("/").last
        case _ =>
      }
      stay

    case Event(WatchEndpointInserted(watchKey, key, _, true), _) =>
      watchKey match {
        case `inProgressContainerPrefixKey` =>
          creationIds += key.split("/").last
        case `existingContainerPrefixKey` =>
          containers += key.split("/").last
        case _ =>
      }
      stay

    // common case for Running, NamespaceThrottled, ActionThrottled
    case Event(SuccessfulCreationJob(creationId, _, _, _), _) =>
      creationIds -= creationId.asString
      stay()

    // for other cases
    case Event(FailedCreationJob(creationId, invocationNamespace, action, revision, _, message), _) =>
      creationIds -= creationId.asString
      logging.info(
        this,
        s"[$invocationNamespace:$action:$stateName][$creationId] Got failed creation job with revision $revision and error $message.")
      stay()

    // common case for Running, NamespaceThrottled, ActionThrottled, Removing
    case Event(cancel: CancelPoll, _) =>
      cancel.promise.trySuccess(Left(NoActivationMessage()))

      stay

    // common case for Running, NamespaceThrottled, ActionThrottled, Removing
    case Event(msg: ActivationMessage, _) =>
      handleActivationMessage(msg)

    // common case for Running, NamespaceThrottled, ActionThrottled, Removing
    case Event(request: GetActivation, _) if request.action == action =>
      implicit val tid = request.transactionId
      if (request.alive) {
        containers += request.containerId
        handleActivationRequest(request)
      } else {
        logging.info(this, s"Remove containerId because ${request.containerId} is not alive")
        sender ! GetActivationResponse(Left(NoActivationMessage()))
        containers -= request.containerId
        stay
      }

    // common case for Running, NamespaceThrottled, ActionThrottled, Removing
    case Event(request: GetActivation, _) if request.action != action =>
      implicit val tid = request.transactionId
      logging.warn(this, s"[$invocationNamespace:$action:$stateName] version mismatch ${request.action}")
      sender ! GetActivationResponse(Left(ActionMismatch()))

      stay

    case Event(DropOld, _) =>
      if (queue.nonEmpty && Duration
            .between(queue.head.timestamp, Instant.now)
            .compareTo(Duration.ofMillis(queueConfig.maxRetentionMs)) < 0) {
        logging.error(
          this,
          s"[$invocationNamespace:$action:$stateName] Drop some stale activations for $revision, existing container is ${containers.size}, inProgress container is ${creationIds.size}, state data: $stateData, in is $in, current: ${queue.size}.")
        logging.error(
          this,
          s"[$invocationNamespace:$action:$stateName] the head stale message: ${queue.head.msg.activationId}")
      }
      queue = MemoryQueue.dropOld(queue, Duration.ofMillis(queueConfig.maxRetentionMs), completeErrorActivation)

      stay

    // common case for all statuses
    case Event(StatusQuery, _) =>
      sender ! StatusData(invocationNamespace, action.asString, queue.size, stateName.toString, stateData.toString)
      stay

    // Common case for all cases
    case Event(GracefulShutdown, data) =>
      logging.info(this, s"[$invocationNamespace:$action:$stateName] Gracefully shutdown the memory queue.")
      // delete relative data, e.g leaderKey, namespaceThrottlingKey, actionThrottlingKey
      cleanUpData()

      // let queue manager knows this queue is going to stop and let it forward incoming activations to a new queue
      context.parent ! queueRemovedMsg

      goto(Removing) using getRemovingData(data, outdated = false)

    // the version is updated. it's a shared case for all states
    case Event(StopSchedulingAsOutdated, data) =>
      logging.info(this, s"[$invocationNamespace:$action:$stateName] stop further scheduling.")
      // let QueueManager know this queue is no longer in charge.
      context.parent ! staleQueueRemovedMsg

      goto(Removing) using getRemovingData(data, outdated = true)

    case Event(t: FailureMessage, _) =>
      logging.error(this, s"[$invocationNamespace:$action:$stateName] got an unexpected failure message: $t")

      stay

    case Event(msg: DecisionResults, _) =>
      val DecisionResults(result, num) = msg
      result match {
        case AddInitialContainer if num > 0 =>
          initialized = true
          val msgs = generateContainerCreationMessages(num)
          containerManager ! ContainerCreation(msgs, memory, invocationNamespace)

        case AddContainer if num > 0 =>
          val msgs = generateContainerCreationMessages(num)
          containerManager ! ContainerCreation(msgs, memory, invocationNamespace)

        case enable: EnableNamespaceThrottling =>
          if (num > 0) {
            val msgs = generateContainerCreationMessages(num)
            containerManager ! ContainerCreation(msgs, memory, invocationNamespace)
          }
          self ! enable

        case DisableNamespaceThrottling =>
          if (num > 0) {
            val msgs = generateContainerCreationMessages(num)
            containerManager ! ContainerCreation(msgs, memory, invocationNamespace)
          }
          self ! DisableNamespaceThrottling

        case Pausing =>
          logging.warn(
            this,
            s"[$invocationNamespace:$action:$stateName] The limit value is less than 0. No activation can be handled so the queue becomes the Flushing state.")
          self ! FailedCreationJob(
            CreationId.void,
            invocationNamespace,
            action,
            revision,
            ZeroNamespaceLimit,
            namespaceLimitUnderZero)
      }
      stay

    // this should not happen
    case otherMsg =>
      logging.warn(this, s"[$invocationNamespace:$action:$stateName] received unexpected message: $otherMsg")

      stay
  }

  onTransition {
    case Uninitialized -> _ => unstashAll()
    case _ -> Flushing      => startTimerWithFixedDelay("StopQueue", StateTimeout, queueConfig.flushGrace)
    case Flushing -> _      => cancelTimer("StopQueue")
  }

  onTermination {
    case _ =>
      // logscheduler must be canceled when FSM is terminated
      logScheduler.cancel()

      // the lifecycle of DecisionMaker conforms to the one of MemoryQueue
      actorSystem.stop(decisionMaker)
  }

  initialize()

  private def cleanUpDataAndGotoRemoved() = {
    cleanUpWatcher()
    cleanUpData()
    context.parent ! queueRemovedMsg

    goto(Removed) using NoData()
  }

  private def cleanUpActorsAndGotoRemoved(data: FlushingData) = {
    cleanUpActors(data)
    cleanUpData()

    context.parent ! queueRemovedMsg

    goto(Removed) using NoData()
  }

  private def cleanUpActorsAndGotoRemovedIfPossible(data: RemovingData) = {
    requestBuffer = requestBuffer.filter(!_.promise.isCompleted)
    if (queue.isEmpty && requestBuffer.isEmpty) {
      logging.info(this, s"[$invocationNamespace:$action:$stateName] No activation exist. Shutdown the queue.")
      // it can be safely called multiple times as it's idempotent
      cleanUpActors(data)

      // if the queue is outdated, remove old containers.
      if (data.outdated) {
        // let the container manager know this version of containers are outdated.
        containerManager ! ContainerDeletion(invocationNamespace, action, revision, actionMetaData)
      }
      self ! QueueRemovedCompleted

      goto(Removed) using NoData()
    } else {
      logging.info(
        this,
        s"[$invocationNamespace:$action:$stateName] Queue is going to stop but there are still ${queue.size} activations and ${requestBuffer.size} request buffered.")
      stay // waiting for next timeout
    }
  }

  private def getRemovingData(data: MemoryQueueData, outdated: Boolean): MemoryQueueData = {
    data match {
      case RunningData(schedulerActor, droppingActor) =>
        RemovingData(schedulerActor, droppingActor, outdated)
      case ThrottledData(schedulerActor, droppingActor) =>
        RemovingData(schedulerActor, droppingActor, outdated)
      case FlushingData(schedulerActor, droppingActor, _, _, _) =>
        RemovingData(schedulerActor, droppingActor, outdated)
      case data: RemovingData =>
        data.copy(outdated = outdated)
      case _ =>
        NoData()
    }
  }
  private def cleanUpWatcher(): Unit = {
    watchedKeys.foreach { key =>
      watcherService ! UnwatchEndpoint(key, isPrefix = true, watcherName)
    }
    watcherService ! UnwatchEndpoint(leaderKey, isPrefix = false, watcherName)
    namespaceContainerCount.close()
  }

  private def cleanUpActors(data: MemoryQueueData): Unit = {
    cleanUpWatcher()

    data match {
      case RunningData(schedulerActor, droppingActor) =>
        actorSystem.stop(schedulerActor)
        actorSystem.stop(droppingActor)

      case ThrottledData(schedulerActor, droppingActor) =>
        actorSystem.stop(schedulerActor)
        actorSystem.stop(droppingActor)

      case FlushingData(schedulerActor, droppingActor, _, _, _) =>
        actorSystem.stop(schedulerActor)
        actorSystem.stop(droppingActor)

      case RemovingData(schedulerActor, droppingActor, _) =>
        actorSystem.stop(schedulerActor)
        actorSystem.stop(droppingActor)

      case _ => // do nothing
    }
  }

  private def cleanUpData(): Unit = {
    dataManagementService ! UnregisterData(leaderKey)
    dataManagementService ! UnregisterData(namespaceThrottlingKey)
    dataManagementService ! UnregisterData(actionThrottlingKey)
  }

  private def initializeThrottling() = {
    dataManagementService ! RegisterInitialData(namespaceThrottlingKey, false.toString, failoverEnabled = false)
    dataManagementService ! RegisterData(actionThrottlingKey, false.toString, failoverEnabled = false)
  }

  private def tryEnableActionThrottling() = {
    if (queue.size >= queueConfig.maxRetentionSize && stateName != ActionThrottled) {
      logging.info(this, s"[$invocationNamespace:$action:$stateName] Enable action throttling.")
      dataManagementService ! RegisterData(actionThrottlingKey, true.toString, failoverEnabled = false)

      stateData match {
        case RunningData(schedulerActor, droppingActor) =>
          goto(ActionThrottled) using ThrottledData(schedulerActor, droppingActor)
        case _ =>
          stay
      }
    } else {
      stay
    }
  }

  private def tryDisableActionThrottling()(implicit tid: TransactionId) = {
    (stateName, stateData) match {
      case (ActionThrottled, ThrottledData(schedulerActor, droppingActor))
          if queue.size <= queueConfig.maxRetentionSize * queueConfig.throttlingFraction =>
        logging.info(this, s"[$invocationNamespace:$action:$stateName] Disable action throttling.")
        dataManagementService ! RegisterData(actionThrottlingKey, false.toString, failoverEnabled = false)

        // at this point, namespace throttling might be enabled,
        // then the state will be changed to NamespaceThrottled automatically at the next tick
        goto(Running) using RunningData(schedulerActor, droppingActor)
      case _ => stay
    }
  }

  private def disableNamespaceThrottling() = {
    dataManagementService ! RegisterData(namespaceThrottlingKey, false.toString, failoverEnabled = false)
  }

  private def enableNamespaceThrottling() = {
    dataManagementService ! RegisterData(namespaceThrottlingKey, true.toString, failoverEnabled = false)
  }

  private def completeErrorActivation(activation: ActivationMessage,
                                      message: String,
                                      isWhiskError: Boolean): Future[Any] = {
    logging.error(
      this,
      s"[$invocationNamespace:$action:$stateName] complete activation ${activation.activationId} with error $message")(
      activation.transid)
    val activationResponse =
      if (isWhiskError)
        generateFallbackActivation(activation, ActivationResponse.whiskError(message))
      else
        generateFallbackActivation(activation, ActivationResponse.developerError(message))

    // TODO change scheduler instance id
    val instance = InvokerInstanceId(0, userMemory = 0.MB)

    val ackMsg = if (activation.blocking) {
      CombinedCompletionAndResultMessage(activation.transid, activationResponse, instance)
    } else {
      CompletionMessage(activation.transid, activationResponse, instance)
    }

    if (!isWhiskError && message == tooManyConcurrentRequests) {
      val metric = Metric("ConcurrentRateLimit", 1)
      UserEvents.send(
        messagingProducer,
        EventMessage(
          schedulerId.toString,
          metric,
          activation.user.subject,
          invocationNamespace,
          activation.user.namespace.uuid,
          metric.typeName))
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
          logging.error(this, s"[$invocationNamespace:$action:$stateName] failed to send ack due to $t")
      }
    store(activation.transid, activationResponse, UserContext(activation.user))
      .andThen {
        case Failure(t) =>
          logging.error(this, s"[$invocationNamespace:$action:$stateName] failed to store activation due to $t")
      }
  }

  private def forwardAllActivations(queueManager: ActorRef): Unit = {
    while (queue.nonEmpty) {
      val (TimeSeriesActivationEntry(_, msg), newQueue) = queue.dequeue
      queue = newQueue
      logging.info(this, s"Forward msg ${msg.activationId} to the queue manager")(msg.transid)
      queueManager ! msg
    }
  }

  private def completeAllActivations(reason: String, isWhiskError: Boolean): Unit = {
    while (queue.nonEmpty) {
      val (TimeSeriesActivationEntry(_, msg), newQueue) = queue.dequeue
      queue = newQueue
      completeErrorActivation(msg, reason, isWhiskError)
    }
  }

  // since there is no initial delay, it will try to create a container at initialization time
  // these schedulers will run forever and stop when the memory queue stops
  private def startMonitoring(): (ActorRef, ActorRef) = {
    val droppingScheduler = Scheduler.scheduleWaitAtLeast(dropInterval) { () =>
      checkToDropStaleActivation(queue, queueConfig.maxRetentionMs, invocationNamespace, action, stateName, self)
      Future.successful(())
    }

    val monitoringScheduler = Scheduler.scheduleWaitAtLeast(checkInterval) { () =>
      // the average duration is updated every checkInterval
      if (averageDurationBuffer.nonEmpty) {
        averageDuration = Some(averageDurationBuffer.average)
      }
      getUserLimit(invocationNamespace).andThen {
        case Success(limit) =>
          decisionMaker ! QueueSnapshot(
            initialized,
            in,
            queue.size,
            containers.size,
            creationIds.size,
            getStaleActivationNum(0, queue),
            namespaceContainerCount.existingContainerNumByNamespace,
            namespaceContainerCount.inProgressContainerNumByNamespace,
            averageDuration,
            limit,
            stateName,
            self)
        case Failure(_: NoDocumentException) =>
          // no limit available for the namespace
          self ! StopSchedulingAsOutdated
      }
    }
    (monitoringScheduler, droppingScheduler)
  }

  private def getAverageDuration() = {
    // check the duration only once
    actorSystem.scheduler.scheduleOnce(duration.Duration.Zero) {
      durationChecker.checkAverageDuration(invocationNamespace, actionMetaData) { durationCheckResult =>
        if (durationCheckResult.hitCount > 0) {
          averageDuration = durationCheckResult.averageDuration
        }
        durationCheckResult
      }
    }
  }

  @tailrec
  private def getStaleActivationNum(count: Int, queue: Queue[TimeSeriesActivationEntry]): Int = {
    if (queue.isEmpty || Duration
          .between(queue.head.timestamp, Instant.now)
          .compareTo(StaleDuration) < 0) count
    else
      getStaleActivationNum(count + 1, queue.tail)
  }

  private def generateContainerCreationMessages(num: Int) = {
    (1 to num).map { _ =>
      val msg = ContainerCreationMessage(
        TransactionId.containerCreation,
        invocationNamespace,
        action,
        revision,
        actionMetaData,
        schedulerId,
        endpoints.host,
        endpoints.rpcPort)
      creationIds += msg.creationId.asString
      logging.info(
        this,
        s"[$invocationNamespace:$action:$stateName] Try to create a new container with creationId ${msg.creationId.asString}")
      msg
    }.toList
  }

  /* take the first uncompleted request from requestBuffer. */
  private def takeUncompletedRequest(): Option[Promise[Either[MemoryQueueError, ActivationMessage]]] = {
    requestBuffer = requestBuffer.filter(!_.promise.isCompleted)
    if (requestBuffer.nonEmpty) {
      Some(requestBuffer.dequeue.promise)
    } else None
  }

  private def handleActivationMessage(msg: ActivationMessage) = {
    logging.info(this, s"[$invocationNamespace:$action:$stateName] got a new activation message ${msg.activationId}")(
      msg.transid)
    in.incrementAndGet()
    takeUncompletedRequest()
      .map { res =>
        res.trySuccess(Right(msg))
        in.decrementAndGet()
        stay
      }
      .getOrElse {
        queue = queue.enqueue(TimeSeriesActivationEntry(Instant.now, msg))
        in.decrementAndGet()
        tryEnableActionThrottling()
      }
  }

  private def handleActivationRequest(request: GetActivation)(implicit tid: TransactionId) = {
    request.lastDuration.foreach(averageDurationBuffer.add(_))

    if (queue.nonEmpty) {
      val (TimeSeriesActivationEntry(_, msg), newQueue) = queue.dequeue
      queue = newQueue
      logging.info(
        this,
        s"[$invocationNamespace:$action:$stateName] Get activation request ${request.containerId}, send one message: ${msg.activationId}")
      sender ! GetActivationResponse(Right(msg))
      tryDisableActionThrottling()
    } else {
      pollForActivation(sender, request)
      stay
    }
  }

  /**
   * Save promise in a Queue, once new activationMessage come, complete the promise with it, if timeout(1s), complete the
   * promise with NoActivationMessage
   */
  private def pollForActivation(sender: ActorRef, request: GetActivation)(implicit tid: TransactionId): Unit = {
    val promise = Promise[Either[MemoryQueueError, ActivationMessage]]()
    val cancelPoll = actorSystem.scheduler.scheduleOnce(pollTimeOut) {
      self ! CancelPoll(promise)
    }

    // "1xxx" is always bigger than "0xxx", so warmed containers will be took first while dequeue from `requestBuffer`
    val warmedFlag = if (request.warmed) 1 else 0
    requestBuffer.enqueue(BufferedRequest(warmedFlag + request.containerId, promise))
    promise.future.onComplete {
      case Success(value) =>
        sender ! GetActivationResponse(value)
        value match {
          case Right(msg) =>
            logging.info(
              this,
              s"[$invocationNamespace:$action:$stateName] Send msg ${msg.activationId} to waiting request ${request.containerId}")
            cancelPoll.cancel()
          case Left(_) => // do nothing
        }
      case Failure(t) => // this shouldn't happen
        logging.error(
          this,
          s"[$invocationNamespace:$action:$stateName] Unexpected error ${t.getMessage} while poll for activation.")
        sender ! GetActivationResponse(Left(NoActivationMessage()))
        cancelPoll.cancel()
    }
  }

  /** Generates an activation with zero runtime. Usually used for error cases */
  private def generateFallbackActivation(msg: ActivationMessage, response: ActivationResponse): WhiskActivation = {
    val now = Instant.now
    val causedBy = if (msg.causedBySequence) {
      Some(Parameters(WhiskActivation.causedByAnnotation, JsString(Exec.SEQUENCE)))
    } else None

    val limits = Parameters(WhiskActivation.limitsAnnotation, actionMetaData.limits.toJson)
    val binding =
      actionMetaData.binding.map(f => Parameters(WhiskActivation.bindingAnnotation, JsString(f.asString)))

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
          Parameters(WhiskActivation.kindAnnotation, JsString(actionMetaData.exec.kind)) ++
          causedBy ++ limits ++ binding
      })
  }
}

object MemoryQueue {
  private[queue] val queueConfig = loadConfigOrThrow[QueueConfig](ConfigKeys.schedulerQueue)
  private[queue] val MaxRetentionTime = queueConfig.maxRetentionMs

  def props(etcdClient: EtcdClient,
            durationChecker: DurationChecker,
            fqn: FullyQualifiedEntityName,
            messagingProducer: MessageProducer,
            config: WhiskConfig,
            invocationNamespace: String,
            revision: DocRevision,
            endpoints: SchedulerEndpoints,
            actionMetaData: WhiskActionMetaData,
            dataManagementService: ActorRef,
            watcherService: ActorRef,
            containerManager: ActorRef,
            decisionMaker: ActorRef,
            schedulerId: SchedulerInstanceId,
            ack: ActiveAck,
            store: (TransactionId, WhiskActivation, UserContext) => Future[Any],
            getUserLimit: String => Future[Int])(implicit logging: Logging): Props = {
    Props(
      new MemoryQueue(
        etcdClient,
        durationChecker,
        fqn: FullyQualifiedEntityName,
        messagingProducer: MessageProducer,
        config: WhiskConfig,
        invocationNamespace: String,
        revision,
        endpoints: SchedulerEndpoints,
        actionMetaData,
        dataManagementService,
        watcherService,
        containerManager,
        decisionMaker,
        schedulerId,
        ack,
        store,
        getUserLimit,
        checkToDropStaleActivation,
        queueConfig))
  }

  @tailrec
  def dropOld(
    queue: Queue[TimeSeriesActivationEntry],
    retention: Duration,
    completeErrorActivation: (ActivationMessage, String, Boolean) => Future[Any]): Queue[TimeSeriesActivationEntry] = {
    if (queue.isEmpty || Duration.between(queue.head.timestamp, Instant.now).compareTo(retention) < 0)
      queue
    else {
      completeErrorActivation(queue.head.msg, s"activation processing is not initiated for $MaxRetentionTime ms", true)
      dropOld(queue.tail, retention, completeErrorActivation)
    }
  }

  def checkToDropStaleActivation(queue: Queue[TimeSeriesActivationEntry],
                                 maxRetentionMs: Long,
                                 invocationNamespace: String,
                                 action: FullyQualifiedEntityName,
                                 stateName: MemoryQueueState,
                                 queueRef: ActorRef)(implicit logging: Logging) = {
    if (queue.nonEmpty && Duration
          .between(queue.head.timestamp, Instant.now)
          .compareTo(Duration.ofMillis(maxRetentionMs)) >= 0) {
      logging.info(
        this,
        s"[$invocationNamespace:$action:$stateName] some activations are stale msg: ${queue.head.msg.activationId}.")

      queueRef ! DropOld
    }
  }
}

case class QueueSnapshot(initialized: Boolean,
                         incomingMsgCount: AtomicInteger,
                         currentMsgCount: Int,
                         existingContainerCount: Int,
                         inProgressContainerCount: Int,
                         staleActivationNum: Int,
                         existingContainerCountInNamespace: Int,
                         inProgressContainerCountInNamespace: Int,
                         averageDuration: Option[Double],
                         limit: Int,
                         stateName: MemoryQueueState,
                         recipient: ActorRef)

case class QueueConfig(idleGrace: FiniteDuration,
                       stopGrace: FiniteDuration,
                       flushGrace: FiniteDuration,
                       gracefulShutdownTimeout: FiniteDuration,
                       maxRetentionSize: Int,
                       maxRetentionMs: Long,
                       throttlingFraction: Double,
                       durationBufferSize: Int)

case class BufferedRequest(containerId: String, promise: Promise[Either[MemoryQueueError, ActivationMessage]])
case object DropOld

case class ContainerKeyMeta(revision: DocRevision, invokerId: Int, containerId: String)

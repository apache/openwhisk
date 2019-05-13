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

package org.apache.openwhisk.core.containerpool

import java.time.Instant
import akka.actor.Status.{Failure => FailureMessage}
import akka.actor.{FSM, Props, Stash}
import akka.event.Logging.InfoLevel
import akka.pattern.pipe
import pureconfig.loadConfigOrThrow
import scala.collection.immutable
import spray.json.DefaultJsonProtocol._
import spray.json._
import org.apache.openwhisk.common.{AkkaLogging, Counter, LoggingMarkers, TransactionId}
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.connector.ActivationMessage
import org.apache.openwhisk.core.containerpool.logging.LogCollectingException
import org.apache.openwhisk.core.database.UserContext
import org.apache.openwhisk.core.entity.ExecManifest.ImageName
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.invoker.InvokerReactive.ActiveAck
import org.apache.openwhisk.http.Messages
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

// States
sealed trait ContainerState
case object Uninitialized extends ContainerState
case object Starting extends ContainerState
case object Started extends ContainerState
case object Running extends ContainerState
case object Ready extends ContainerState
case object Pausing extends ContainerState
case object Paused extends ContainerState
case object Removing extends ContainerState

// Data
/** Base data type */
sealed abstract class ContainerData(val lastUsed: Instant, val memoryLimit: ByteSize, val activeActivationCount: Int) {

  /** When ContainerProxy in this state is scheduled, it may result in a new state (ContainerData)*/
  def nextRun(r: Run): ContainerData

  /**
   *  Return Some(container) (for ContainerStarted instances) or None(for ContainerNotStarted instances)
   *  Useful for cases where all ContainerData instances are handled, vs cases where only ContainerStarted
   *  instances are handled */
  def getContainer: Option[Container]

  /** String to indicate the state of this container after scheduling */
  val initingState: String

  /** Inidicates whether this container can service additional activations */
  def hasCapacity(): Boolean
}

/** abstract type to indicate an unstarted container */
sealed abstract class ContainerNotStarted(override val lastUsed: Instant,
                                          override val memoryLimit: ByteSize,
                                          override val activeActivationCount: Int)
    extends ContainerData(lastUsed, memoryLimit, activeActivationCount) {
  override def getContainer = None
  override val initingState = "cold"
}

/** abstract type to indicate a started container */
sealed abstract class ContainerStarted(val container: Container,
                                       override val lastUsed: Instant,
                                       override val memoryLimit: ByteSize,
                                       override val activeActivationCount: Int)
    extends ContainerData(lastUsed, memoryLimit, activeActivationCount) {
  override def getContainer = Some(container)
}

/** trait representing a container that is in use and (potentially) usable by subsequent or concurrent activations */
sealed abstract trait ContainerInUse {
  val activeActivationCount: Int
  val action: ExecutableWhiskAction
  def hasCapacity() =
    activeActivationCount < action.limits.concurrency.maxConcurrent
}

/** trait representing a container that is NOT in use and is usable by subsequent activation(s) */
sealed abstract trait ContainerNotInUse {
  def hasCapacity() = true
}

/** type representing a cold (not running) container */
case class NoData(override val activeActivationCount: Int = 0)
    extends ContainerNotStarted(Instant.EPOCH, 0.B, activeActivationCount)
    with ContainerNotInUse {
  override def nextRun(r: Run) = WarmingColdData(r.msg.user.namespace.name, r.action, Instant.now, 1)
}

/** type representing a cold (not running) container with specific memory allocation */
case class MemoryData(override val memoryLimit: ByteSize, override val activeActivationCount: Int = 0)
    extends ContainerNotStarted(Instant.EPOCH, memoryLimit, activeActivationCount)
    with ContainerNotInUse {
  override def nextRun(r: Run) = WarmingColdData(r.msg.user.namespace.name, r.action, Instant.now, 1)
}

/** type representing a prewarmed (running, but unused) container (with a specific memory allocation) */
case class PreWarmedData(override val container: Container,
                         kind: String,
                         override val memoryLimit: ByteSize,
                         override val activeActivationCount: Int = 0)
    extends ContainerStarted(container, Instant.EPOCH, memoryLimit, activeActivationCount)
    with ContainerNotInUse {
  override val initingState = "prewarmed"
  override def nextRun(r: Run) =
    WarmingData(container, r.msg.user.namespace.name, r.action, Instant.now, 1)
}

/** type representing a prewarm (running, but not used) container that is being initialized (for a specific action + invocation namespace) */
case class WarmingData(override val container: Container,
                       invocationNamespace: EntityName,
                       action: ExecutableWhiskAction,
                       override val lastUsed: Instant,
                       override val activeActivationCount: Int = 0)
    extends ContainerStarted(container, lastUsed, action.limits.memory.megabytes.MB, activeActivationCount)
    with ContainerInUse {
  override val initingState = "warming"
  override def nextRun(r: Run) = copy(activeActivationCount = activeActivationCount + 1)
}

/** type representing a cold (not yet running) container that is being initialized (for a specific action + invocation namespace) */
case class WarmingColdData(invocationNamespace: EntityName,
                           action: ExecutableWhiskAction,
                           override val lastUsed: Instant,
                           override val activeActivationCount: Int = 0)
    extends ContainerNotStarted(lastUsed, action.limits.memory.megabytes.MB, activeActivationCount)
    with ContainerInUse {
  override val initingState = "warmingCold"
  override def nextRun(r: Run) = copy(activeActivationCount = activeActivationCount + 1)
}

/** type representing a warm container that has already been in use (for a specific action + invocation namespace) */
case class WarmedData(override val container: Container,
                      invocationNamespace: EntityName,
                      action: ExecutableWhiskAction,
                      override val lastUsed: Instant,
                      override val activeActivationCount: Int = 0)
    extends ContainerStarted(container, lastUsed, action.limits.memory.megabytes.MB, activeActivationCount)
    with ContainerInUse {
  override val initingState = "warmed"
  override def nextRun(r: Run) = copy(activeActivationCount = activeActivationCount + 1)
}

// Events received by the actor
case class Start(exec: CodeExec[_], memoryLimit: ByteSize)
case class Run(action: ExecutableWhiskAction, msg: ActivationMessage, retryLogDeadline: Option[Deadline] = None)
case object Remove

// Events sent by the actor
case class NeedWork(data: ContainerData)
case object ContainerPaused
case object ContainerRemoved // when container is destroyed
case object RescheduleJob // job is sent back to parent and could not be processed because container is being destroyed
case class PreWarmCompleted(data: PreWarmedData)
case class InitCompleted(data: WarmedData)
case object RunCompleted

/**
 * A proxy that wraps a Container. It is used to keep track of the lifecycle
 * of a container and to guarantee a contract between the client of the container
 * and the container itself.
 *
 * The contract is as follows:
 * 1. If action.limits.concurrency.maxConcurrent == 1:
 *    Only one job is to be sent to the ContainerProxy at one time. ContainerProxy
 *    will delay all further jobs until a previous job has finished.
 *
 *    1a. The next job can be sent to the ContainerProxy after it indicates available
 *       capacity by sending NeedWork to its parent.
 *
 * 2. If action.limits.concurrency.maxConcurrent > 1:
 *    Parent must coordinate with ContainerProxy to attempt to send only data.action.limits.concurrency.maxConcurrent
 *    jobs for concurrent processing.
 *
 *    Since the current job count is only periodically sent to parent, the number of jobs
 *    sent to ContainerProxy may exceed data.action.limits.concurrency.maxConcurrent,
 *    in which case jobs are buffered, so that only a max of action.limits.concurrency.maxConcurrent
 *    are ever sent into the container concurrently. Parent will NOT be signalled to send more jobs until
 *    buffered jobs are completed, but their order is not guaranteed.
 *
 *    2a. The next job can be sent to the ContainerProxy after ContainerProxy has "concurrent capacity",
 *        indicated by sending NeedWork to its parent.
 *
 * 3. A Remove message can be sent at any point in time. Like multiple jobs though,
 *    it will be delayed until the currently running job finishes.
 *
 * @constructor
 * @param factory a function generating a Container
 * @param sendActiveAck a function sending the activation via active ack
 * @param storeActivation a function storing the activation in a persistent store
 * @param unusedTimeout time after which the container is automatically thrown away
 * @param pauseGrace time to wait for new work before pausing the container
 */
class ContainerProxy(
  factory: (TransactionId, String, ImageName, Boolean, ByteSize, Int) => Future[Container],
  sendActiveAck: ActiveAck,
  storeActivation: (TransactionId, WhiskActivation, UserContext) => Future[Any],
  collectLogs: (TransactionId, Identity, WhiskActivation, Container, ExecutableWhiskAction) => Future[ActivationLogs],
  instance: InvokerInstanceId,
  poolConfig: ContainerPoolConfig,
  unusedTimeout: FiniteDuration,
  pauseGrace: FiniteDuration)
    extends FSM[ContainerState, ContainerData]
    with Stash {
  implicit val ec = context.system.dispatcher
  implicit val logging = new AkkaLogging(context.system.log)
  var rescheduleJob = false // true iff actor receives a job but cannot process it because actor will destroy itself
  var runBuffer = immutable.Queue.empty[Run] //does not retain order, but does manage jobs that would have pushed past action concurrency limit

  //keep a separate count to avoid confusion with ContainerState.activeActivationCount that is tracked/modified only in ContainerPool
  var activeCount = 0;
  startWith(Uninitialized, NoData())

  when(Uninitialized) {
    // pre warm a container (creates a stem cell container)
    case Event(job: Start, _) =>
      factory(
        TransactionId.invokerWarmup,
        ContainerProxy.containerName(instance, "prewarm", job.exec.kind),
        job.exec.image,
        job.exec.pull,
        job.memoryLimit,
        poolConfig.cpuShare(job.memoryLimit))
        .map(container => PreWarmCompleted(PreWarmedData(container, job.exec.kind, job.memoryLimit)))
        .pipeTo(self)

      goto(Starting)

    // cold start (no container to reuse or available stem cell container)
    case Event(job: Run, _) =>
      implicit val transid = job.msg.transid
      activeCount += 1
      // create a new container
      val container = factory(
        job.msg.transid,
        ContainerProxy.containerName(instance, job.msg.user.namespace.name.asString, job.action.name.asString),
        job.action.exec.image,
        job.action.exec.pull,
        job.action.limits.memory.megabytes.MB,
        poolConfig.cpuShare(job.action.limits.memory.megabytes.MB))

      // container factory will either yield a new container ready to execute the action, or
      // starting up the container failed; for the latter, it's either an internal error starting
      // a container or a docker action that is not conforming to the required action API
      container
        .andThen {
          case Success(container) =>
            // the container is ready to accept an activation; register it as PreWarmed; this
            // normalizes the life cycle for containers and their cleanup when activations fail
            self ! PreWarmCompleted(
              PreWarmedData(container, job.action.exec.kind, job.action.limits.memory.megabytes.MB, 1))

          case Failure(t) =>
            // the container did not come up cleanly, so disambiguate the failure mode and then cleanup
            // the failure is either the system fault, or for docker actions, the application/developer fault
            val response = t match {
              case WhiskContainerStartupError(msg) => ActivationResponse.whiskError(msg)
              case BlackboxStartupError(msg)       => ActivationResponse.developerError(msg)
              case _                               => ActivationResponse.whiskError(Messages.resourceProvisionError)
            }
            val context = UserContext(job.msg.user)
            // construct an appropriate activation and record it in the datastore,
            // also update the feed and active ack; the container cleanup is queued
            // implicitly via a FailureMessage which will be processed later when the state
            // transitions to Running
            val activation = ContainerProxy.constructWhiskActivation(job, None, Interval.zero, false, response)
            sendActiveAck(
              transid,
              activation,
              job.msg.blocking,
              job.msg.rootControllerIndex,
              job.msg.user.namespace.uuid,
              true)
            storeActivation(transid, activation, context)
        }
        .flatMap { container =>
          // now attempt to inject the user code and run the action
          initializeAndRun(container, job)
            .map(_ => RunCompleted)
        }
        .pipeTo(self)

      goto(Running)
  }

  when(Starting) {
    // container was successfully obtained
    case Event(completed: PreWarmCompleted, _) =>
      context.parent ! NeedWork(completed.data)
      goto(Started) using completed.data

    // container creation failed
    case Event(_: FailureMessage, _) =>
      context.parent ! ContainerRemoved
      stop()

    case _ => delay
  }

  when(Started) {
    case Event(job: Run, data: PreWarmedData) =>
      implicit val transid = job.msg.transid
      activeCount += 1
      initializeAndRun(data.container, job)
        .map(_ => RunCompleted)
        .pipeTo(self)
      goto(Running) using PreWarmedData(data.container, data.kind, data.memoryLimit, 1)

    case Event(Remove, data: PreWarmedData) => destroyContainer(data.container)
  }

  when(Running) {
    // Intermediate state, we were able to start a container
    // and we keep it in case we need to destroy it.
    case Event(completed: PreWarmCompleted, _) => stay using completed.data

    // Init was successful
    case Event(completed: InitCompleted, _: PreWarmedData) =>
      stay using completed.data

    // Init was successful
    case Event(data: WarmedData, _: PreWarmedData) =>
      //in case concurrency supported, multiple runs can begin as soon as init is complete
      context.parent ! NeedWork(data)
      stay using data

    // Run was successful
    case Event(RunCompleted, data: WarmedData) =>
      activeCount -= 1

      //if there are items in runbuffer, process them if there is capacity, and stay; otherwise if we have any pending activations, also stay
      if (requestWork(data) || activeCount > 0) {
        stay using data
      } else {
        goto(Ready) using data
      }
    case Event(job: Run, data: WarmedData)
        if activeCount >= data.action.limits.concurrency.maxConcurrent && !rescheduleJob => //if we are over concurrency limit, and not a failure on resume
      logging.warn(this, s"buffering for container ${data.container}; ${activeCount} activations in flight")
      runBuffer = runBuffer.enqueue(job)
      stay()
    case Event(job: Run, data: WarmedData)
        if activeCount < data.action.limits.concurrency.maxConcurrent && !rescheduleJob => //if there was a delay, and not a failure on resume, skip the run
      activeCount += 1
      implicit val transid = job.msg.transid

      initializeAndRun(data.container, job)
        .map(_ => RunCompleted)
        .pipeTo(self)
      stay() using data

    // Failed after /init (the first run failed)
    case Event(_: FailureMessage, data: PreWarmedData) =>
      activeCount -= 1
      destroyContainer(data.container)

    // Failed for a subsequent /run
    case Event(_: FailureMessage, data: WarmedData) =>
      activeCount -= 1
      destroyContainer(data.container)

    // Failed at getting a container for a cold-start run
    case Event(_: FailureMessage, _) =>
      activeCount -= 1
      context.parent ! ContainerRemoved
      rejectBuffered()
      stop()

    case _ => delay
  }

  when(Ready, stateTimeout = pauseGrace) {
    case Event(job: Run, data: WarmedData) =>
      implicit val transid = job.msg.transid
      activeCount += 1

      initializeAndRun(data.container, job)
        .map(_ => RunCompleted)
        .pipeTo(self)

      goto(Running) using data

    // pause grace timed out
    case Event(StateTimeout, data: WarmedData) =>
      data.container.suspend()(TransactionId.invokerNanny).map(_ => ContainerPaused).pipeTo(self)
      goto(Pausing)

    case Event(Remove, data: WarmedData) => destroyContainer(data.container)
  }

  when(Pausing) {
    case Event(ContainerPaused, data: WarmedData)   => goto(Paused)
    case Event(_: FailureMessage, data: WarmedData) => destroyContainer(data.container)
    case _                                          => delay
  }

  when(Paused, stateTimeout = unusedTimeout) {
    case Event(job: Run, data: WarmedData) =>
      implicit val transid = job.msg.transid
      activeCount += 1

      data.container
        .resume()
        .andThen {
          // Sending the message to self on a failure will cause the message
          // to ultimately be sent back to the parent (which will retry it)
          // when container removal is done.
          case Failure(_) =>
            rescheduleJob = true
            self ! job
        }
        .flatMap(_ => initializeAndRun(data.container, job))
        .map(_ => RunCompleted)
        .pipeTo(self)

      goto(Running) using data

    // container is reclaimed by the pool or it has become too old
    case Event(StateTimeout | Remove, data: WarmedData) =>
      rescheduleJob = true // to supress sending message to the pool and not double count
      destroyContainer(data.container)
  }

  when(Removing) {
    case Event(job: Run, _) =>
      // Send the job back to the pool to be rescheduled
      context.parent ! job
      stay
    case Event(ContainerRemoved, _)  => stop()
    case Event(_: FailureMessage, _) => stop()
  }

  // Unstash all messages stashed while in intermediate state
  onTransition {
    case _ -> Started  => unstashAll()
    case _ -> Ready    => unstashAll()
    case _ -> Paused   => unstashAll()
    case _ -> Removing => unstashAll()
  }

  initialize()

  /** Either process runbuffer or signal parent to send work; return true if runbuffer is being processed */
  def requestWork(newData: WarmedData): Boolean = {
    //if there is concurrency capacity, process runbuffer, or signal NeedWork
    if (activeCount < newData.action.limits.concurrency.maxConcurrent) {
      runBuffer.dequeueOption match {
        case Some((run, q)) =>
          runBuffer = q
          self ! run
          true
        case _ =>
          context.parent ! NeedWork(newData)
          false
      }
    } else {
      false
    }
  }

  /** Delays all incoming messages until unstashAll() is called */
  def delay = {
    stash()
    stay
  }

  /**
   * Destroys the container after unpausing it if needed. Can be used
   * as a state progression as it goes to Removing.
   *
   * @param container the container to destroy
   */
  def destroyContainer(container: Container) = {
    if (!rescheduleJob) {
      context.parent ! ContainerRemoved
    } else {
      context.parent ! RescheduleJob
    }

    rejectBuffered()

    val unpause = stateName match {
      case Paused => container.resume()(TransactionId.invokerNanny)
      case _      => Future.successful(())
    }

    unpause
      .flatMap(_ => container.destroy()(TransactionId.invokerNanny))
      .map(_ => ContainerRemoved)
      .pipeTo(self)

    goto(Removing)
  }

  /**
   * Return any buffered jobs to parent, in case buffer is not empty at removal/error time.
   */
  def rejectBuffered() = {
    //resend any buffered items on container removal
    if (runBuffer.nonEmpty) {
      logging.info(this, s"resending ${runBuffer.size} buffered jobs to parent on container removal")
      runBuffer.foreach(context.parent ! _)
      runBuffer = immutable.Queue.empty[Run]
    }
  }

  /**
   * Runs the job, initialize first if necessary.
   * Completes the job by:
   * 1. sending an activate ack,
   * 2. fetching the logs for the run,
   * 3. indicating the resource is free to the parent pool,
   * 4. recording the result to the data store
   *
   * @param container the container to run the job on
   * @param job the job to run
   * @return a future completing after logs have been collected and
   *         added to the WhiskActivation
   */
  def initializeAndRun(container: Container, job: Run)(implicit tid: TransactionId): Future[WhiskActivation] = {
    val actionTimeout = job.action.limits.timeout.duration

    // Only initialize iff we haven't yet warmed the container
    val initialize = stateData match {
      case data: WarmedData =>
        Future.successful(None)
      case _ =>
        container
          .initialize(job.action.containerInitializer, actionTimeout, job.action.limits.concurrency.maxConcurrent)
          .map(Some(_))
    }

    val activation: Future[WhiskActivation] = initialize
      .flatMap { initInterval =>
        //immediately setup warmedData for use (before first execution) so that concurrent actions can use it asap
        if (initInterval.isDefined) {
          self ! InitCompleted(WarmedData(container, job.msg.user.namespace.name, job.action, Instant.now, 1))
        }
        val parameters = job.msg.content getOrElse JsObject.empty

        // if the action requests the api key to be injected into the action context, add it here;
        // treat a missing annotation as requesting the api key for backward compatibility
        val authEnvironment = {
          if (job.action.annotations.isTruthy(WhiskAction.provideApiKeyAnnotationName, valueForNonExistent = true)) {
            job.msg.user.authkey.toEnvironment
          } else JsObject.empty
        }

        val environment = JsObject(
          "namespace" -> job.msg.user.namespace.name.toJson,
          "action_name" -> job.msg.action.qualifiedNameWithLeadingSlash.toJson,
          "activation_id" -> job.msg.activationId.toString.toJson,
          // compute deadline on invoker side avoids discrepancies inside container
          // but potentially under-estimates actual deadline
          "deadline" -> (Instant.now.toEpochMilli + actionTimeout.toMillis).toString.toJson)

        container
          .run(
            parameters,
            JsObject(authEnvironment.fields ++ environment.fields),
            actionTimeout,
            job.action.limits.concurrency.maxConcurrent)(job.msg.transid)
          .map {
            case (runInterval, response) =>
              val initRunInterval = initInterval
                .map(i => Interval(runInterval.start.minusMillis(i.duration.toMillis), runInterval.end))
                .getOrElse(runInterval)
              ContainerProxy.constructWhiskActivation(
                job,
                initInterval,
                initRunInterval,
                runInterval.duration >= actionTimeout,
                response)
          }
      }
      .recover {
        case InitializationError(interval, response) =>
          ContainerProxy.constructWhiskActivation(
            job,
            Some(interval),
            interval,
            interval.duration >= actionTimeout,
            response)
        case t =>
          // Actually, this should never happen - but we want to make sure to not miss a problem
          logging.error(this, s"caught unexpected error while running activation: ${t}")
          ContainerProxy.constructWhiskActivation(
            job,
            None,
            Interval.zero,
            false,
            ActivationResponse.whiskError(Messages.abnormalRun))
      }

    // Sending an active ack is an asynchronous operation. The result is forwarded as soon as
    // possible for blocking activations so that dependent activations can be scheduled. The
    // completion message which frees a load balancer slot is sent after the active ack future
    // completes to ensure proper ordering.
    val sendResult = if (job.msg.blocking) {
      activation.map(
        sendActiveAck(tid, _, job.msg.blocking, job.msg.rootControllerIndex, job.msg.user.namespace.uuid, false))
    } else {
      // For non-blocking request, do not forward the result.
      Future.successful(())
    }

    val context = UserContext(job.msg.user)

    // Adds logs to the raw activation.
    val activationWithLogs: Future[Either[ActivationLogReadingError, WhiskActivation]] = activation
      .flatMap { activation =>
        // Skips log collection entirely, if the limit is set to 0
        if (job.action.limits.logs.asMegaBytes == 0.MB) {
          Future.successful(Right(activation))
        } else {
          val start = tid.started(this, LoggingMarkers.INVOKER_COLLECT_LOGS, logLevel = InfoLevel)
          collectLogs(tid, job.msg.user, activation, container, job.action)
            .andThen {
              case Success(_) => tid.finished(this, start)
              case Failure(t) => tid.failed(this, start, s"reading logs failed: $t")
            }
            .map(logs => Right(activation.withLogs(logs)))
            .recover {
              case LogCollectingException(logs) =>
                Left(ActivationLogReadingError(activation.withLogs(logs)))
              case _ =>
                Left(ActivationLogReadingError(activation.withLogs(ActivationLogs(Vector(Messages.logFailure)))))
            }
        }
      }

    activationWithLogs
      .map(_.fold(_.activation, identity))
      .foreach { activation =>
        // Sending the completion message to the controller after the active ack ensures proper ordering
        // (result is received before the completion message for blocking invokes).
        sendResult.onComplete(
          _ =>
            sendActiveAck(
              tid,
              activation,
              job.msg.blocking,
              job.msg.rootControllerIndex,
              job.msg.user.namespace.uuid,
              true))
        // Storing the record. Entirely asynchronous and not waited upon.
        storeActivation(tid, activation, context)
      }

    // Disambiguate activation errors and transform the Either into a failed/successful Future respectively.
    activationWithLogs.flatMap {
      case Right(act) if !act.response.isSuccess && !act.response.isApplicationError =>
        Future.failed(ActivationUnsuccessfulError(act))
      case Left(error) => Future.failed(error)
      case Right(act)  => Future.successful(act)
    }
  }
}

final case class ContainerProxyTimeoutConfig(idleContainer: FiniteDuration, pauseGrace: FiniteDuration)

object ContainerProxy {
  def props(
    factory: (TransactionId, String, ImageName, Boolean, ByteSize, Int) => Future[Container],
    ack: (TransactionId, WhiskActivation, Boolean, ControllerInstanceId, UUID, Boolean) => Future[Any],
    store: (TransactionId, WhiskActivation, UserContext) => Future[Any],
    collectLogs: (TransactionId, Identity, WhiskActivation, Container, ExecutableWhiskAction) => Future[ActivationLogs],
    instance: InvokerInstanceId,
    poolConfig: ContainerPoolConfig,
    unusedTimeout: FiniteDuration = timeouts.idleContainer,
    pauseGrace: FiniteDuration = timeouts.pauseGrace) =
    Props(new ContainerProxy(factory, ack, store, collectLogs, instance, poolConfig, unusedTimeout, pauseGrace))

  // Needs to be thread-safe as it's used by multiple proxies concurrently.
  private val containerCount = new Counter

  val timeouts = loadConfigOrThrow[ContainerProxyTimeoutConfig](ConfigKeys.containerProxyTimeouts)

  /**
   * Generates a unique container name.
   *
   * @param prefix the container name's prefix
   * @param suffix the container name's suffix
   * @return a unique container name
   */
  def containerName(instance: InvokerInstanceId, prefix: String, suffix: String): String = {
    def isAllowed(c: Char): Boolean = c.isLetterOrDigit || c == '_'

    val sanitizedPrefix = prefix.filter(isAllowed)
    val sanitizedSuffix = suffix.filter(isAllowed)

    s"${ContainerFactory.containerNamePrefix(instance)}_${containerCount.next()}_${sanitizedPrefix}_${sanitizedSuffix}"
  }

  /**
   * Creates a WhiskActivation ready to be sent via active ack.
   *
   * @param job the job that was executed
   * @param interval the time it took to execute the job
   * @param response the response to return to the user
   * @return a WhiskActivation to be sent to the user
   */
  def constructWhiskActivation(job: Run,
                               initInterval: Option[Interval],
                               totalInterval: Interval,
                               isTimeout: Boolean,
                               response: ActivationResponse) = {
    val causedBy = Some {
      if (job.msg.causedBySequence) {
        Parameters(WhiskActivation.causedByAnnotation, JsString(Exec.SEQUENCE))
      } else {
        // emit the internal system hold time as the 'wait' time, but only for non-sequence
        // actions, since the transid start time for a sequence does not correspond
        // with a specific component of the activation but the entire sequence;
        // it will require some work to generate a new transaction id for a sequence
        // component - however, because the trace of activations is recorded in the parent
        // sequence, a client can determine the queue time for sequences that way
        val end = initInterval.map(_.start).getOrElse(totalInterval.start)
        Parameters(
          WhiskActivation.waitTimeAnnotation,
          Interval(job.msg.transid.meta.start, end).duration.toMillis.toJson)
      }
    }

    val initTime = {
      initInterval.map(initTime => Parameters(WhiskActivation.initTimeAnnotation, initTime.duration.toMillis.toJson))
    }

    val binding =
      job.msg.action.binding.map(f => Parameters(WhiskActivation.bindingAnnotation, JsString(f.asString)))

    WhiskActivation(
      activationId = job.msg.activationId,
      namespace = job.msg.user.namespace.name.toPath,
      subject = job.msg.user.subject,
      cause = job.msg.cause,
      name = job.action.name,
      version = job.action.version,
      start = totalInterval.start,
      end = totalInterval.end,
      duration = Some(totalInterval.duration.toMillis),
      response = response,
      annotations = {
        Parameters(WhiskActivation.limitsAnnotation, job.action.limits.toJson) ++
          Parameters(WhiskActivation.pathAnnotation, JsString(job.action.fullyQualifiedName(false).asString)) ++
          Parameters(WhiskActivation.kindAnnotation, JsString(job.action.exec.kind)) ++
          Parameters(WhiskActivation.timeoutAnnotation, JsBoolean(isTimeout)) ++
          causedBy ++ initTime ++ binding
      })
  }
}

/** Indicates that something went wrong with an activation and the container should be removed */
trait ActivationError extends Exception {
  val activation: WhiskActivation
}

/** Indicates an activation with a non-successful response */
case class ActivationUnsuccessfulError(activation: WhiskActivation) extends ActivationError

/** Indicates reading logs for an activation failed (terminally, truncated) */
case class ActivationLogReadingError(activation: WhiskActivation) extends ActivationError

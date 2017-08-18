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

package whisk.core.containerpool

import java.time.Instant

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Success
import scala.util.Failure

import akka.actor.FSM
import akka.actor.Props
import akka.actor.Stash
import akka.actor.Status.{Failure => FailureMessage}
import akka.pattern.pipe
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.common.TransactionId
import whisk.core.connector.ActivationMessage
import whisk.core.entity._
import whisk.core.entity.size._
import whisk.common.Counter
import whisk.core.entity.ExecManifest.ImageName
import whisk.common.AkkaLogging
import whisk.http.Messages

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
sealed abstract class ContainerData(val lastUsed: Instant)
case class NoData() extends ContainerData(Instant.EPOCH)
case class PreWarmedData(container: Container, kind: String, memoryLimit: ByteSize) extends ContainerData(Instant.EPOCH)
case class WarmedData(container: Container,
                      invocationNamespace: EntityName,
                      action: ExecutableWhiskAction,
                      override val lastUsed: Instant)
    extends ContainerData(lastUsed)

// Events received by the actor
case class Start(exec: CodeExec[_], memoryLimit: ByteSize)
case class Run(action: ExecutableWhiskAction, msg: ActivationMessage)
case object Remove

// Events sent by the actor
case class NeedWork(data: ContainerData)
case object ContainerPaused
case object ContainerRemoved

/**
 * A proxy that wraps a Container. It is used to keep track of the lifecycle
 * of a container and to guarantee a contract between the client of the container
 * and the container itself.
 *
 * The contract is as follows:
 * 1. Only one job is to be sent to the ContainerProxy at one time. ContainerProxy
 *    will delay all further jobs until a previous job has finished.
 * 2. The next job can be sent to the ContainerProxy after it indicates available
 *    capacity by sending NeedWork to its parent.
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
class ContainerProxy(factory: (TransactionId, String, ImageName, Boolean, ByteSize) => Future[Container],
                     sendActiveAck: (TransactionId, WhiskActivation, Boolean, InstanceId) => Future[Any],
                     storeActivation: (TransactionId, WhiskActivation) => Future[Any],
                     instance: InstanceId,
                     unusedTimeout: FiniteDuration,
                     pauseGrace: FiniteDuration)
    extends FSM[ContainerState, ContainerData]
    with Stash {
  implicit val ec = context.system.dispatcher
  val logging = new AkkaLogging(context.system.log)

  startWith(Uninitialized, NoData())

  when(Uninitialized) {
    // pre warm a container (creates a stem cell container)
    case Event(job: Start, _) =>
      factory(
        TransactionId.invokerWarmup,
        ContainerProxy.containerName(instance, "prewarm", job.exec.kind),
        job.exec.image,
        job.exec.pull,
        job.memoryLimit)
        .map(container => PreWarmedData(container, job.exec.kind, job.memoryLimit))
        .pipeTo(self)

      goto(Starting)

    // cold start (no container to reuse or available stem cell container)
    case Event(job: Run, _) =>
      implicit val transid = job.msg.transid

      // create a new container
      val container = factory(
        job.msg.transid,
        ContainerProxy.containerName(instance, job.msg.user.namespace.name, job.action.name.name),
        job.action.exec.image,
        job.action.exec.pull,
        job.action.limits.memory.megabytes.MB)

      // container factory will either yield a new container ready to execute the action, or
      // starting up the container failed; for the latter, it's either an internal error starting
      // a container or a docker action that is not conforming to the required action API
      container
        .andThen {
          case Success(container) =>
            // the container is ready to accept an activation; register it as PreWarmed; this
            // normalizes the life cycle for containers and their cleanup when activations fail
            self ! PreWarmedData(container, job.action.exec.kind, job.action.limits.memory.megabytes.MB)

          case Failure(t) =>
            // the container did not come up cleanly, so disambiguate the failure mode and then cleanup
            // the failure is either the system fault, or for docker actions, the application/developer fault
            val response = t match {
              case WhiskContainerStartupError(msg) => ActivationResponse.whiskError(msg)
              case BlackboxStartupError(msg)       => ActivationResponse.applicationError(msg)
              case _                               => ActivationResponse.whiskError(t.getMessage)
            }
            // construct an appropriate activation and record it in the datastore,
            // also update the feed and active ack; the container cleanup is queued
            // implicitly via a FailureMessage which will be processed later when the state
            // transitions to Running
            val activation = ContainerProxy.constructWhiskActivation(job, Interval.zero, response)
            sendActiveAck(transid, activation, job.msg.blocking, job.msg.rootControllerIndex)
            storeActivation(transid, activation)
        }
        .flatMap { container =>
          // now attempt to inject the user code and run the action
          initializeAndRun(container, job)
            .map(_ => WarmedData(container, job.msg.user.namespace, job.action, Instant.now))
        }
        .pipeTo(self)

      goto(Running)
  }

  when(Starting) {
    // container was successfully obtained
    case Event(data: PreWarmedData, _) =>
      context.parent ! NeedWork(data)
      goto(Started) using data

    // container creation failed
    case Event(_: FailureMessage, _) =>
      context.parent ! ContainerRemoved
      stop()

    case _ => delay
  }

  when(Started) {
    case Event(job: Run, data: PreWarmedData) =>
      implicit val transid = job.msg.transid
      initializeAndRun(data.container, job)
        .map(_ => WarmedData(data.container, job.msg.user.namespace, job.action, Instant.now))
        .pipeTo(self)

      goto(Running)

    case Event(Remove, data: PreWarmedData) => destroyContainer(data.container)
  }

  when(Running) {
    // Intermediate state, we were able to start a container
    // and we keep it in case we need to destroy it.
    case Event(data: PreWarmedData, _) => stay using data

    // Run was successful
    case Event(data: WarmedData, _) =>
      context.parent ! NeedWork(data)
      goto(Ready) using data

    // Failed after /init (the first run failed)
    case Event(_: FailureMessage, data: PreWarmedData) => destroyContainer(data.container)

    // Failed for a subsequent /run
    case Event(_: FailureMessage, data: WarmedData) => destroyContainer(data.container)

    // Failed at getting a container for a cold-start run
    case Event(_: FailureMessage, _) =>
      context.parent ! ContainerRemoved
      stop()

    case _ => delay
  }

  when(Ready, stateTimeout = pauseGrace) {
    case Event(job: Run, data: WarmedData) =>
      implicit val transid = job.msg.transid
      initializeAndRun(data.container, job)
        .map(_ => WarmedData(data.container, job.msg.user.namespace, job.action, Instant.now))
        .pipeTo(self)

      goto(Running)

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
      data.container
        .resume()
        .andThen {
          // Sending the message to self on a failure will cause the message
          // to ultimately be sent back to the parent (which will retry it)
          // when container removal is done.
          case Failure(_) => self ! job
        }
        .flatMap(_ => initializeAndRun(data.container, job))
        .map(_ => WarmedData(data.container, job.msg.user.namespace, job.action, Instant.now))
        .pipeTo(self)

      goto(Running)

    // timeout or removing
    case Event(StateTimeout | Remove, data: WarmedData) => destroyContainer(data.container)
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
    context.parent ! ContainerRemoved

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
      case data: WarmedData => Future.successful(Interval.zero)
      case _                => container.initialize(job.action.containerInitializer, actionTimeout)
    }

    val activation: Future[WhiskActivation] = initialize
      .flatMap { initInterval =>
        val parameters = job.msg.content getOrElse JsObject()

        val environment = JsObject(
          "api_key" -> job.msg.user.authkey.compact.toJson,
          "namespace" -> job.msg.user.namespace.toJson,
          "action_name" -> job.msg.action.qualifiedNameWithLeadingSlash.toJson,
          "activation_id" -> job.msg.activationId.toString.toJson,
          // compute deadline on invoker side avoids discrepancies inside container
          // but potentially under-estimates actual deadline
          "deadline" -> (Instant.now.toEpochMilli + actionTimeout.toMillis).toString.toJson)

        container.run(parameters, environment, actionTimeout)(job.msg.transid).map {
          case (runInterval, response) =>
            val initRunInterval =
              Interval(runInterval.start.minusMillis(initInterval.duration.toMillis), runInterval.end)
            ContainerProxy.constructWhiskActivation(job, initRunInterval, response)
        }
      }
      .recover {
        case InitializationError(interval, response) =>
          ContainerProxy.constructWhiskActivation(job, interval, response)
        case t =>
          // Actually, this should never happen - but we want to make sure to not miss a problem
          logging.error(this, s"caught unexpected error while running activation: ${t}")
          ContainerProxy.constructWhiskActivation(
            job,
            Interval.zero,
            ActivationResponse.whiskError(Messages.abnormalRun))
      }

    // Sending active ack and storing the activation are concurrent side-effects
    // and do not block further execution of the future. They are completely
    // asynchronous.
    activation
      .andThen {
        // the activation future will always complete with Success
        case Success(ack) => sendActiveAck(tid, ack, job.msg.blocking, job.msg.rootControllerIndex)
      }
      .flatMap { activation =>
        container.logs(job.action.limits.logs.asMegaBytes, job.action.exec.sentinelledLogs).map { logs =>
          activation.withLogs(ActivationLogs(logs.toVector))
        }
      }
      .andThen {
        case Success(activation) => storeActivation(tid, activation)
      }
      .flatMap { activation =>
        // Fail the future iff the activation was unsuccessful to facilitate
        // better cleanup logic.
        if (activation.response.isSuccess) Future.successful(activation)
        else Future.failed(ActivationUnsuccessfulError(activation))
      }
  }
}

object ContainerProxy {
  def props(factory: (TransactionId, String, ImageName, Boolean, ByteSize) => Future[Container],
            ack: (TransactionId, WhiskActivation, Boolean, InstanceId) => Future[Any],
            store: (TransactionId, WhiskActivation) => Future[Any],
            instance: InstanceId,
            unusedTimeout: FiniteDuration = 10.minutes,
            pauseGrace: FiniteDuration = 50.milliseconds) =
    Props(new ContainerProxy(factory, ack, store, instance, unusedTimeout, pauseGrace))

  // Needs to be thread-safe as it's used by multiple proxies concurrently.
  private val containerCount = new Counter

  /**
   * Generates a unique container name.
   *
   * @param prefix the container name's prefix
   * @param suffix the container name's suffix
   * @return a unique container name
   */
  def containerName(instance: InstanceId, prefix: String, suffix: String) =
    s"wsk${instance.toInt}_${containerCount.next()}_${prefix}_${suffix}".replaceAll("[^a-zA-Z0-9_]", "")

  /**
   * Creates a WhiskActivation ready to be sent via active ack.
   *
   * @param job the job that was executed
   * @param interval the time it took to execute the job
   * @param response the response to return to the user
   * @return a WhiskActivation to be sent to the user
   */
  def constructWhiskActivation(job: Run, interval: Interval, response: ActivationResponse) = {
    val causedBy = if (job.msg.causedBySequence) Parameters("causedBy", "sequence".toJson) else Parameters()
    WhiskActivation(
      activationId = job.msg.activationId,
      namespace = job.msg.activationNamespace,
      subject = job.msg.user.subject,
      cause = job.msg.cause,
      name = job.action.name,
      version = job.action.version,
      start = interval.start,
      end = interval.end,
      duration = Some(interval.duration.toMillis),
      response = response,
      annotations = {
        Parameters("limits", job.action.limits.toJson) ++
          Parameters("path", job.action.fullyQualifiedName(false).toString.toJson) ++ causedBy
      })
  }
}

/** Indicates an activation with a non-successful response */
case class ActivationUnsuccessfulError(activation: WhiskActivation)
    extends Exception(s"activation ${activation.activationId} failed")

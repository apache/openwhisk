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

package whisk.core.containerpool

import akka.actor.FSM
import akka.actor.Props
import akka.pattern.pipe
import scala.concurrent.Future
import scala.concurrent.duration._
import akka.actor.Stash
import whisk.core.entity.WhiskAction
import whisk.core.connector.ActivationMessage
import whisk.core.entity.WhiskActivation
import scala.util.Success
import whisk.core.entity.ActivationResponse
import akka.actor.Status.{ Failure => FailureMessage }
import whisk.core.entity.ActivationLogs
import whisk.common.TransactionId
import scala.util.Failure
import whisk.core.entity.ByteSize
import whisk.core.entity.size._
import whisk.core.entity.Exec
import whisk.core.entity.EntityName
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import whisk.core.entity.CodeExec
import whisk.core.entity.Parameters

import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.core.container.Interval

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
case class PreWarmedData(container: Container, kind: String) extends ContainerData(Instant.EPOCH)
case class WarmedData(container: Container, namespace: EntityName, action: WhiskAction, override val lastUsed: Instant) extends ContainerData(lastUsed)

// Events
sealed trait Job
case class Start(exec: Exec) extends Job
case class Run(action: WhiskAction, msg: ActivationMessage) extends Job
case object Remove

case class NeedWork(data: ContainerData)

// Container events
case class ContainerCreated(container: Container, kind: Exec)
case object ContainerPaused
case object ContainerRemoved

class WhiskContainer(
    factory: (TransactionId, String, Exec, ByteSize) => Future[Container],
    sendActiveAck: (TransactionId, WhiskActivation) => Future[Any],
    storeActivation: (TransactionId, WhiskActivation) => Future[Any]) extends FSM[ContainerState, ContainerData] with Stash {
    implicit val ec = context.system.dispatcher

    val unusedTimeout = 30.seconds
    val pauseGrace = 1.second

    startWith(Uninitialized, NoData())

    when(Uninitialized) {
        // pre warm a container
        case Event(job: Start, _) =>
            factory(
                TransactionId.invokerWarmup,
                WhiskContainer.containerName("prewarm", job.exec.kind),
                job.exec,
                256.MB)
                .map(container => PreWarmedData(container, job.exec.kind))
                .pipeTo(self)

            goto(Starting)

        // cold start
        case Event(job: Run, _) =>
            implicit val transid = job.msg.transid
            factory(
                job.msg.transid,
                WhiskContainer.containerName(job.msg.user.namespace.name, job.action.name.name),
                job.action.exec,
                job.action.limits.memory.megabytes.MB)
                .andThen {
                    case Success(container) => self ! PreWarmedData(container, job.action.exec.kind)
                    case Failure(t) =>
                        val response = t match {
                            case WhiskContainerStartupError(msg) => ActivationResponse.whiskError(msg)
                            case BlackboxStartupError(msg)       => ActivationResponse.applicationError(msg)
                            case _                               => ActivationResponse.whiskError(t.getMessage)
                        }
                        val activation = WhiskContainer.constructWhiskActivation(job, Interval.zero, response)
                        sendActiveAck(transid, activation)
                        storeActivation(transid, activation)
                }
                .flatMap {
                    container =>
                        run(container, job)
                            .map(_ => WarmedData(container, job.msg.user.namespace, job.action, Instant.now))
                }.pipeTo(self)

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
            run(data.container, job)
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
        case Event(_: FailureMessage, data: WarmedData)    => destroyContainer(data.container)

        // Failed at getting a container for a cold-start run
        case Event(_: FailureMessage, _) =>
            context.parent ! ContainerRemoved
            stop()

        case _ => delay
    }

    when(Ready, stateTimeout = pauseGrace) {
        case Event(job: Run, data: WarmedData) =>
            implicit val transid = job.msg.transid
            run(data.container, job)
                .map(_ => WarmedData(data.container, job.msg.user.namespace, job.action, Instant.now))
                .pipeTo(self)

            goto(Running)

        // pause grace timed out
        case Event(StateTimeout, data: WarmedData) =>
            data.container.halt()(TransactionId.invokerNanny).map(_ => ContainerPaused).pipeTo(self)
            goto(Pausing)

        case Event(Remove, data: WarmedData) => destroyContainer(data.container)
    }

    when(Pausing) {
        case Event(ContainerPaused, data: WarmedData) =>
            context.parent ! NeedWork(data)
            goto(Paused)

        case Event(_: FailureMessage, data: WarmedData) => destroyContainer(data.container)
        case _ => delay
    }

    when(Paused, stateTimeout = unusedTimeout) {
        case Event(job: Run, data: WarmedData) =>
            implicit val transid = job.msg.transid
            data.container.resume()
                .flatMap(_ => run(data.container, job))
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
        case Event(ContainerRemoved, _) => stop()
    }

    // Unstash all messages stashed while in intermediate state
    onTransition {
        case _ -> Started => unstashAll()
        case _ -> Ready   => unstashAll()
        case _ -> Paused  => unstashAll()
    }

    initialize()

    /** Delays all incoming messages until unstashAll() is called */
    def delay = {
        stash()
        stay
    }

    /**
     * Destroys the container after unpausing it if needed
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
            .map(_ => ContainerRemoved).pipeTo(self)

        goto(Removing)
    }

    /**
     * Runs the job, initialize first if necessary.
     *
     * @param container the container to run the job on
     * @param job the job to run
     * @return
     */
    def run(container: Container, job: Run)(implicit tid: TransactionId): Future[WhiskActivation] = {
        val actionTimeout = job.action.limits.timeout.duration
        val initialize = stateData match {
            case data: WarmedData => Future.successful(Interval.zero)
            case _                => container.initialize(job.action.containerInitializer, actionTimeout)
        }

        val activation: Future[WhiskActivation] = initialize.flatMap { initInterval =>
            val passedParameters = job.msg.content getOrElse JsObject()
            val boundParameters = job.action.parameters.toJsObject
            val parameters = JsObject(boundParameters.fields ++ passedParameters.fields)

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
                    val initRunInterval = Interval(runInterval.start.minusMillis(initInterval.duration.toMillis), runInterval.end)
                    WhiskContainer.constructWhiskActivation(job, initRunInterval, response)
            }
        }.recover {
            case InitializationError(response, interval) =>
                WhiskContainer.constructWhiskActivation(job, interval, response)
        }

        // Sending active ack and storing the activation are concurrent side-effects
        // and do not block the future.
        activation.andThen {
            case Success(activation) => sendActiveAck(tid, activation)
        }.flatMap { activation =>
            val exec = job.action.exec.asInstanceOf[CodeExec[_]]
            container.logs(job.action.limits.logs.asMegaBytes, exec.sentinelledLogs).map { logs =>
                activation.withLogs(ActivationLogs(logs.toVector))
            }
        }.andThen {
            case Success(activation) => storeActivation(tid, activation)
        }.flatMap { activation =>
            // Fail the future iff the activation was unsuccessful to facilitate
            // better cleanup logic.
            if (activation.response.isSuccess) Future.successful(activation)
            else Future.failed(new Exception())
        }
    }
}

object WhiskContainer {
    def props(factory: (TransactionId, String, Exec, ByteSize) => Future[Container],
              ack: (TransactionId, WhiskActivation) => Future[Any],
              store: (TransactionId, WhiskActivation) => Future[Any]) = Props(new WhiskContainer(factory, ack, store))

    private val count = new AtomicInteger(0)
    def containerNumber() = count.incrementAndGet()

    def containerName(namespace: String, actionName: String) =
        s"wsk_${containerNumber()}_${namespace}_${actionName}".replaceAll("[^a-zA-Z0-9_]", "")

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

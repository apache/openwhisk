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
import whisk.core.connector.CompletionMessage
import whisk.core.connector.MessageProducer
import scala.util.Success
import whisk.core.entity.ActivationResponse
import akka.actor.Status.{ Failure => FailureMessage }
import whisk.core.entity.ActivationLogs
import whisk.core.entity.types.ActivationStore
import whisk.common.TransactionId
import whisk.common.AkkaLogging
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
case object Unpausing extends ContainerState
case object Removing extends ContainerState

// Data
sealed abstract class ContainerData(val lastUsed: Instant)
case class NoData(override val lastUsed: Instant) extends ContainerData(lastUsed)

sealed abstract class ContainerDataWithContainer(val container: Container, override val lastUsed: Instant) extends ContainerData(lastUsed)
case class PreWarmedData(
    override val container: Container,
    kind: String,
    override val lastUsed: Instant) extends ContainerDataWithContainer(container, lastUsed)
case class WarmedData(
    override val container: Container,
    namespace: EntityName,
    action: WhiskAction,
    override val lastUsed: Instant) extends ContainerDataWithContainer(container, lastUsed)

// Events
sealed trait Job
case class Start(exec: Exec) extends Job
case class Run(action: WhiskAction, msg: ActivationMessage) extends Job
case object Remove

case class NeedWork(data: ContainerData)

// Container events
case class ContainerCreated(container: Container, kind: Exec)
case object ActivationCompleted
case object ContainerPaused
case object ContainerUnpaused
case object ContainerRemoved

class WhiskContainer(
    factory: (TransactionId, String, Exec, ByteSize) => Future[Container],
    activeAckProducer: MessageProducer,
    store: ActivationStore) extends FSM[ContainerState, ContainerData] with Stash {
    implicit val ec = context.system.dispatcher
    val logging = new AkkaLogging(context.system.log)

    val unusedTimeout = 30.seconds
    val pauseGrace = 1.second

    startWith(Uninitialized, NoData(Instant.EPOCH))

    when(Uninitialized) {
        case Event(job: Start, _) =>
            factory(
                TransactionId.invokerWarmup,
                WhiskContainer.containerName("prewarm", job.exec.kind),
                job.exec,
                256.MB).andThen {
                    case Success(c) =>
                        self ! ContainerCreated(c, job.exec)
                        self ! job
                    case Failure(t) =>
                        self ! FailureMessage(t)
                }

            goto(Starting)

        case Event(job: Run, _) =>
            factory(
                job.msg.transid,
                WhiskContainer.containerName(job.msg.user.namespace.name, job.action.name.name),
                job.action.exec,
                job.action.limits.memory.megabytes.MB).andThen {
                    case Success(c) =>
                        self ! ContainerCreated(c, job.action.exec)
                        self ! job
                    case Failure(t) =>
                        val response = t match {
                            case WhiskContainerStartupError(msg) => ActivationResponse.whiskError(msg)
                            case BlackboxStartupError(msg)       => ActivationResponse.applicationError(msg)
                            case _                               => ActivationResponse.whiskError(t.getMessage)
                        }
                        val now = Instant.now
                        val interval = Interval(now, now)
                        val activation = WhiskContainer.constructWhiskActivation(job, interval, response)
                        sendActiveAck(activation)(job.msg.transid)
                        storeActivation(activation)(job.msg.transid)

                        self ! FailureMessage(t)
                }

            goto(Starting)
    }

    when(Starting) {
        case Event(ContainerCreated(c, exec), _) => goto(Started) using PreWarmedData(c, exec.kind, Instant.EPOCH)
        case Event(FailureMessage(err), _) =>
            self ! ContainerRemoved
            goto(Removing)
        case _ => delay
    }

    when(Started) {
        case Event(job: Start, data: ContainerData) =>
            context.parent ! NeedWork(data)
            stay

        case Event(job: Run, data: ContainerDataWithContainer) =>
            implicit val transid = job.msg.transid

            val actionTimeout = job.action.limits.timeout.duration
            val initialize = data match {
                case p: PreWarmedData => p.container.initialize(job.action.containerInitializer, actionTimeout)
                case _                => Future.successful(Interval(Instant.EPOCH, Instant.EPOCH))
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

                data.container.run(parameters, environment, actionTimeout)(job.msg.transid).map {
                    case (runInterval, response) =>
                        if (!response.isSuccess) {
                            self ! Remove
                        }
                        val initRunInterval = Interval(runInterval.start.minusMillis(initInterval.duration.toMillis), runInterval.end)
                        WhiskContainer.constructWhiskActivation(job, initRunInterval, response)
                }
            }.recover {
                case InitializationError(response, interval) =>
                    self ! Remove
                    WhiskContainer.constructWhiskActivation(job, interval, response)
            }

            activation.andThen {
                case Success(activation) => sendActiveAck(activation)
            }.flatMap { activation =>
                val exec = job.action.exec.asInstanceOf[CodeExec[_]]
                data.container.logs(job.action.limits.logs.asMegaBytes, exec.sentinelledLogs).map { logs =>
                    activation.withLogs(ActivationLogs(logs.toVector))
                }
            }.andThen {
                case Success(activation) =>
                    self ! ActivationCompleted
                    storeActivation(activation)
            }

            goto(Running) using WarmedData(data.container, job.msg.user.namespace, job.action, Instant.now)

        case Event(Remove, data: ContainerDataWithContainer) => destroyContainer(data.container)
    }

    when(Running) {
        case Event(ActivationCompleted, _) => goto(Ready)
        case _                             => delay
    }

    when(Ready, stateTimeout = pauseGrace) {
        case Event(run: Run, data: WarmedData) =>
            self ! run
            goto(Started)

        case Event(StateTimeout, data: WarmedData) =>
            data.container.halt()(TransactionId.invokerNanny).map(_ => ContainerPaused).pipeTo(self)
            goto(Pausing)

        case Event(Remove, data: WarmedData) => destroyContainer(data.container)
    }

    when(Pausing) {
        case Event(ContainerPaused, _) => goto(Paused)
        case Event(FailureMessage(_), data: WarmedData) => destroyContainer(data.container)
        case _ => delay
    }

    when(Paused, stateTimeout = unusedTimeout) {
        case Event(run: Run, data: WarmedData) =>
            data.container.resume()(run.msg.transid)
                .map(_ => ContainerUnpaused).pipeTo(self)
                .map(_ => run).pipeTo(self)

            goto(Unpausing)

        case Event(StateTimeout | Remove, data: WarmedData) => destroyContainer(data.container)
    }

    when(Unpausing) {
        case Event(ContainerUnpaused, _) => goto(Started)
        case Event(FailureMessage(_), data: WarmedData) => destroyContainer(data.container)
        case _ => delay
    }

    when(Removing) {
        case Event(job: Run, _)         => stay
        case Event(ContainerRemoved, _) => stop()
    }

    onTransition {
        case _ -> Ready  => context.parent ! NeedWork(stateData)
        case _ -> Paused => context.parent ! NeedWork(stateData)
    }

    // Unstash all messages stashed while in intermediate state
    onTransition {
        case _ -> Started => unstashAll()
        case _ -> Ready   => unstashAll()
        case _ -> Paused  => unstashAll()
    }

    /*onTransition {
        case oldState -> newState => println(s"container went from $oldState to $newState")
    }*/

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
     * Sends an active ack to exit a blocking invocation as early as possible.
     *
     * @param activation the activation that contains run responses etc. but no logs
     */
    def sendActiveAck(activation: WhiskActivation)(implicit transid: TransactionId) = {
        val completion = CompletionMessage(transid, activation)
        activeAckProducer.send("completed", completion).andThen {
            case Success(_) => logging.info(this, s"posted completion of activation ${activation.activationId}")
        }
    }

    /**
     * Stores the activation in the datastore for persistence.
     *
     * @param activation that contains full information, including logs
     */
    def storeActivation(activation: WhiskActivation)(implicit transid: TransactionId) = {
        logging.info(this, "recording the activation result to the data store")
        WhiskActivation.put(store, activation) andThen {
            case Success(id) => logging.info(this, s"recorded activation")
            case Failure(t)  => logging.error(this, s"failed to record activation")
        }
    }
}

object WhiskContainer {
    def props(factory: (TransactionId, String, Exec, ByteSize) => Future[Container], prod: MessageProducer, store: ActivationStore) = Props(new WhiskContainer(factory, prod, store))

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

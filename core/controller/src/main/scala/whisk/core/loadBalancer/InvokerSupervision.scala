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

package whisk.core.loadBalancer

import java.nio.charset.StandardCharsets

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

import org.apache.kafka.clients.producer.RecordMetadata

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorRefFactory
import akka.actor.FSM
import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.actor.Props
import akka.pattern.pipe
import akka.util.Timeout
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.common.AkkaLogging
import whisk.common.ConsulKV.LoadBalancerKeys
import whisk.common.KeyValueStore
import whisk.common.Logging
import whisk.common.LoggingMarkers
import whisk.common.RingBuffer
import whisk.common.TransactionId
import whisk.core.connector.ActivationMessage
import whisk.core.connector.CompletionMessage
import whisk.core.connector.MessageConsumer
import whisk.core.connector.MessageProducer
import whisk.core.connector.PingMessage
import whisk.core.database.NoDocumentException
import whisk.core.entitlement.Privilege.Privilege
import whisk.core.entity.ActivationId.ActivationIdGenerator
import whisk.core.entity.AuthKey
import whisk.core.entity.CodeExecAsString
import whisk.core.entity.DocRevision
import whisk.core.entity.EntityName
import whisk.core.entity.EntityPath
import whisk.core.entity.ExecManifest
import whisk.core.entity.Identity
import whisk.core.entity.Secret
import whisk.core.entity.Subject
import whisk.core.entity.UUID
import whisk.core.entity.WhiskAction
import whisk.core.entity.types.EntityStore

// Received events
case object GetStatus

case object Tick

// States an Invoker can be in
sealed trait InvokerState { val asString: String }
case object Offline extends InvokerState { val asString = "down" }
case object Healthy extends InvokerState { val asString = "up" }
case object UnHealthy extends InvokerState { val asString = "unhealthy" }

case class ActivationRequest(msg: ActivationMessage, invoker: String)
case class InvocationFinishedMessage(name: String, successful: Boolean)

// Data stored in the Invoker
final case class InvokerInfo(buffer: RingBuffer[Boolean])

/**
 * Actor representing a pool of invokers
 *
 * The InvokerPool manages a Invokers through subactors. An new Invoker
 * is registered lazily by sending it a Ping event with the name of the
 * Invoker. Ping events are furthermore forwarded to the respective
 * Invoker for their respective State handling.
 *
 * Note: An Invoker that never sends an initial Ping will not be considered
 * by the InvokerPool and thus might not be caught by monitoring.
 */
class InvokerPool(
    childFactory: (ActorRefFactory, String) => ActorRef,
    kv: KeyValueStore,
    invokerDownCallback: String => Unit,
    producer: MessageProducer,
    activeAckConsumer: MessageConsumer,
    activeAckCallback: CompletionMessage => Unit,
    pingConsumer: MessageConsumer) extends Actor {

    implicit val transid = TransactionId.invokerHealth
    implicit val logging = new AkkaLogging(context.system.log)
    implicit val timeout = Timeout(5.seconds)
    implicit val ec = context.dispatcher

    // State of the actor. It's important not to close over these
    // references directly, so they don't escape the Actor.
    val invokers = mutable.HashMap.empty[String, ActorRef]
    val invokerStatus = mutable.HashMap.empty[String, InvokerState]

    def receive = {
        case p: PingMessage =>
            val invoker = invokers.getOrElseUpdate(p.name, {
                logging.info(this, s"registered a new invoker: ${p.name}")(TransactionId.invokerHealth)
                val ref = childFactory(context, p.name)
                ref ! SubscribeTransitionCallBack(self) // register for state change events
                ref
            })
            invoker.forward(p)

        case GetStatus => sender() ! invokerStatus.toMap

        case msg: InvocationFinishedMessage => {
            // Forward message to invoker, if InvokerActor exists
            invokers.get(msg.name).map(_.forward(msg))
        }

        case msg: ActivationRequest => {
            implicit val transid = msg.msg.transid
            val start = transid.started(this, LoggingMarkers.CONTROLLER_KAFKA, s"posting topic '${msg.invoker}' with activation id '${msg.msg.activationId}'")

            producer.send(msg.invoker, msg.msg).andThen {
                case Success(status) => transid.finished(this, start, s"Posted to ${status.topic()}[${status.partition()}][${status.offset()}]")
                case Failure(e)      => transid.failed(this, start, s"Error on posting to topic ${msg.invoker}")
            }.pipeTo(sender())
        }

        case CurrentState(invoker, currentState: InvokerState) =>
            invokerStatus.update(invoker.path.name, currentState)
            publishStatus()

        case Transition(invoker, oldState: InvokerState, newState: InvokerState) =>
            invokerStatus.update(invoker.path.name, newState)
            newState match {
                case Offline => Future(invokerDownCallback(invoker.path.name))
                case _       =>
            }
            publishStatus()
    }

    def publishStatus() = {
        val json = invokerStatus.toMap.mapValues(_.asString).toJson
        kv.put(LoadBalancerKeys.invokerHealth, json.compactPrint)

        val pretty = invokerStatus.toSeq.sortBy {
            case (name, _) => name.filter(_.isDigit).toInt
        }.map { case (name, state) => s"$name: $state" }
        logging.info(this, s"invoker status changed to ${pretty.mkString(", ")}")
    }

    /** Receive activeAcks from Invoker. */
    activeAckConsumer.onMessage((topic, _, _, bytes) => {
        val raw = new String(bytes, StandardCharsets.UTF_8)
        CompletionMessage.parse(raw) match {
            case Success(m: CompletionMessage) => {
                self ! InvocationFinishedMessage(m.invoker, m.response.response.statusCode <= 2)
                activeAckCallback(m)
            }
            case Failure(t) => logging.error(this, s"failed processing message: $raw with $t")
        }
    })

    /** Receive Ping messages from invokers. */
    pingConsumer.onMessage((topic, _, _, bytes) => {
        val raw = new String(bytes, StandardCharsets.UTF_8)
        PingMessage.parse(raw) match {
            case Success(p: PingMessage) => self ! p
            case Failure(t)              => logging.error(this, s"failed processing message: $raw with $t")
        }
    })
}

object InvokerPool {
    def props(
        f: (ActorRefFactory, String) => ActorRef,
        kv: KeyValueStore,
        cb: String => Unit,
        p: MessageProducer,
        ackC: MessageConsumer,
        acb: CompletionMessage => Unit,
        pc: MessageConsumer) = Props(new InvokerPool(f, kv, cb, p, ackC, acb, pc))

    val testAction = ExecManifest.runtimesManifest.resolveDefaultRuntime("nodejs:6").map { manifest =>
        new WhiskAction(
            namespace = EntityPath("whisk.system"),
            name = EntityName("invokerHealthTestAction"),
            exec = new CodeExecAsString(manifest, """function main(params) { return params || {}; }""", None))
    }

    /** Create an test action on startup if it is not there. */
    def createTestActionForInvokerHealth(db: EntityStore)(implicit logging: Logging, ec: ExecutionContext): Future[WhiskAction] = {
        implicit val tid = TransactionId.loadbalancer
        testAction match {
            case Some(action) =>
                WhiskAction.get(db, action.docid).recover {
                    case _: NoDocumentException => WhiskAction.put(db, action)
                }.map(_ => action).andThen {
                    case Success(_) => logging.info(this, "Testaction for invokerHealth exists now.")
                    case Failure(e) => logging.error(this, s"Error on creating testaction for invokerHealth: $e")
                }
            case None => {
                logging.error(this, "Error on creating testaction for invokerHealth: Probably there were problems with the manifest.")
                Future.failed(new Exception("Error on creating testaction for invokerHealth: Probably there were problems with the manifest."))
            }
        }
    }
}

/**
 * Actor representing an Invoker
 *
 * This finite state-machine represents an Invoker in its possible
 * states "Healthy" and "Offline".
 */
class InvokerActor extends FSM[InvokerState, InvokerInfo] {
    implicit val transid = TransactionId.invokerHealth
    implicit val logging = new AkkaLogging(context.system.log)
    def name = self.path.name

    val healthyTimeout = 10.seconds

    // This is done at this point to not intermingle with the state-machine
    // especially their timeouts.
    def customReceive: Receive = {
        case _: RecordMetadata => // The response of putting testactions to the MessageProducer. We don't have to do anything with them.
    }
    override def receive = customReceive.orElse(super.receive)

    startWith(UnHealthy, InvokerInfo(new RingBuffer[Boolean](InvokerActor.bufferSize)))

    /**
     * An Offline invoker represents an existing but broken
     * invoker. This means, that it does not send pings anymore.
     */
    when(Offline) {
        case Event(_: PingMessage, _) => goto(UnHealthy)
    }

    /**
     * An UnHealthy invoker represents an invoker, that was not able to handle actions successfully.
     */
    when(UnHealthy, stateTimeout = healthyTimeout) {
        case Event(_: PingMessage, _) => stay
        case Event(StateTimeout, _)   => goto(Offline)
        case Event(Tick, info) => {
            invokeTestActions()
            stay
        }
    }

    /**
     * A Healthy invoker is characterized by continuously getting
     * pings. It will go offline if that state is not confirmed
     * for 20 seconds.
     */
    when(Healthy, stateTimeout = healthyTimeout) {
        case Event(_: PingMessage, _) => stay
        case Event(StateTimeout, _)   => goto(Offline)
    }

    whenUnhandled {
        case Event(cm: InvocationFinishedMessage, info) => handleCompletionMessage(cm.successful, info.buffer)
    }

    /** Logging on Transition change */
    onTransition {
        case _ -> Offline   => transid.mark(this, LoggingMarkers.LOADBALANCER_INVOKER_OFFLINE, s"$name is offline", akka.event.Logging.WarningLevel)
        case _ -> UnHealthy => transid.mark(this, LoggingMarkers.LOADBALANCER_INVOKER_UNHEALTHY, s"$name is unhealthy", akka.event.Logging.WarningLevel)
        case _ -> Healthy   => logging.info(this, s"$name is healthy")
    }

    /** Scheduler to send testActivations, if the invoker is UnHealthy */
    onTransition {
        case _ -> UnHealthy => {
            invokeTestActions()
            setTimer(InvokerActor.timerName, Tick, 1.minute, true)
        }
        case UnHealthy -> _ => cancelTimer(InvokerActor.timerName)
    }

    initialize()

    /**
     * Handling for active acks. This method saves the result (successful or unsuccessful)
     * into an RingBuffer and checks, if the InvokerActor has to be changed to UnHealthy.
     *
     * @param wasActivationSuccessful: result of Activation
     * @param buffer to be used
     */
    private def handleCompletionMessage(wasActivationSuccessful: Boolean, buffer: RingBuffer[Boolean]) = {
        buffer.add(wasActivationSuccessful)

        // If the current state is UnHealthy, this means, that the activeAck is from an testaction.
        // If this is successful it seems like the Invoker is Healthy again. So we execute immediately
        // a new testaction to remove the errors out of the RingBuffer as fast as possible.
        if (wasActivationSuccessful && stateName == UnHealthy) {
            invokeTestActions()
        }

        if (stateName == Healthy && wasActivationSuccessful) {
            stay
        } else {
            // Goto UnHealthy if there are more errors than accepted in buffer, else goto Healthy
            if (buffer.toList.count(_ == true) >= InvokerActor.bufferSize - InvokerActor.bufferErrorTolerance) {
                gotoIfNotThere(Healthy)
            } else {
                gotoIfNotThere(UnHealthy)
            }
        }
    }

    /**
     * Creates an Activationmessage with the given action and sends it to the InvokerPool.
     * The InvokerPool redirects it to the invoker which is represented by this InvokerActor.
     */
    private def invokeTestActions() = {
        InvokerPool.testAction.map { action =>
            val activationMessage = ActivationMessage(
                // Use the sid of the InvokerSupervisor as tid
                transid = transid,
                action = action.fullyQualifiedName(true),
                // Use empty DocRevision to force the invoker to pull the action from db all the time
                revision = DocRevision(),
                // Authentication is not needed anymore at this point.
                user = Identity(Subject("unhealthyInvokerCheck"), EntityName("unhealthyInvokerCheck"), AuthKey(UUID(), Secret()), Set[Privilege]()),
                // Create a new Activation ID for this activation
                activationId = new ActivationIdGenerator {}.make(),
                activationNamespace = EntityPath("whisk.system"),
                content = None)

            context.parent ! ActivationRequest(activationMessage, name)
        }
    }

    /**
     * Only change the state if the currentState is not the newState.
     *
     * @param newState of the InvokerActor
     */
    private def gotoIfNotThere(newState: InvokerState) = {
        if (stateName == newState) stay() else goto(newState)
    }
}

object InvokerActor {
    def props() = Props[InvokerActor]

    val bufferSize = 10
    val bufferErrorTolerance = 3

    val timerName = "testActionTimer"
}

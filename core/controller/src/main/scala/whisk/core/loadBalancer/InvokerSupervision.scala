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

package whisk.core.loadBalancer

import java.nio.charset.StandardCharsets

import scala.collection.immutable
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
import whisk.common.AkkaLogging
import whisk.common.LoggingMarkers
import whisk.common.RingBuffer
import whisk.common.TransactionId
import whisk.core.connector._
import whisk.core.entitlement.Privilege
import whisk.core.entity.ActivationId.ActivationIdGenerator
import whisk.core.entity._

// Received events
case object GetStatus

case object Tick

// States an Invoker can be in
sealed trait InvokerState { val asString: String }
case object Offline extends InvokerState { val asString = "down" }
case object Healthy extends InvokerState { val asString = "up" }
case object UnHealthy extends InvokerState { val asString = "unhealthy" }

case class ActivationRequest(msg: ActivationMessage, invoker: InstanceId)
case class InvocationFinishedMessage(invokerInstance: InstanceId, successful: Boolean)

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
class InvokerPool(childFactory: (ActorRefFactory, InstanceId) => ActorRef,
                  sendActivationToInvoker: (ActivationMessage, InstanceId) => Future[RecordMetadata],
                  pingConsumer: MessageConsumer)
    extends Actor {

  implicit val transid = TransactionId.invokerHealth
  implicit val logging = new AkkaLogging(context.system.log)
  implicit val timeout = Timeout(5.seconds)
  implicit val ec = context.dispatcher

  // State of the actor. Mutable vars with immutable collections prevents closures or messages
  // from leaking the state for external mutation
  var instanceToRef = immutable.Map.empty[InstanceId, ActorRef]
  var refToInstance = immutable.Map.empty[ActorRef, InstanceId]
  var status = IndexedSeq[(InstanceId, InvokerState)]()

  def receive = {
    case p: PingMessage =>
      val invoker = instanceToRef.getOrElse(p.instance, registerInvoker(p.instance))
      instanceToRef = instanceToRef.updated(p.instance, invoker)

      invoker.forward(p)

    case GetStatus => sender() ! status

    case msg: InvocationFinishedMessage =>
      // Forward message to invoker, if InvokerActor exists
      instanceToRef.get(msg.invokerInstance).foreach(_.forward(msg))

    case CurrentState(invoker, currentState: InvokerState) =>
      refToInstance.get(invoker).foreach { instance =>
        status = status.updated(instance.toInt, (instance, currentState))
      }
      logStatus()

    case Transition(invoker, oldState: InvokerState, newState: InvokerState) =>
      refToInstance.get(invoker).foreach { instance =>
        status = status.updated(instance.toInt, (instance, newState))
      }
      logStatus()

    // this is only used for the internal test action which enabled an invoker to become healthy again
    case msg: ActivationRequest => sendActivationToInvoker(msg.msg, msg.invoker).pipeTo(sender)
  }

  def logStatus() = {
    val pretty = status.map { case (instance, state) => s"${instance.toInt} -> $state" }
    logging.info(this, s"invoker status changed to ${pretty.mkString(", ")}")
  }

  /** Receive Ping messages from invokers. */
  val pingPollDuration = 1.second
  val invokerPingFeed = context.system.actorOf(Props {
    new MessageFeed(
      "ping",
      logging,
      pingConsumer,
      pingConsumer.maxPeek,
      pingPollDuration,
      processInvokerPing,
      logHandoff = false)
  })

  def processInvokerPing(bytes: Array[Byte]): Future[Unit] = Future {
    val raw = new String(bytes, StandardCharsets.UTF_8)
    PingMessage.parse(raw) match {
      case Success(p: PingMessage) =>
        self ! p
        invokerPingFeed ! MessageFeed.Processed

      case Failure(t) =>
        invokerPingFeed ! MessageFeed.Processed
        logging.error(this, s"failed processing message: $raw with $t")
    }
  }

  /** Pads a list to a given length using the given function to compute entries */
  def padToIndexed[A](list: IndexedSeq[A], n: Int, f: (Int) => A) = list ++ (list.size until n).map(f)

  // Register a new invoker
  def registerInvoker(instanceId: InstanceId): ActorRef = {
    logging.info(this, s"registered a new invoker: invoker${instanceId.toInt}")(TransactionId.invokerHealth)

    status = padToIndexed(status, instanceId.toInt + 1, i => (InstanceId(i), Offline))

    val ref = childFactory(context, instanceId)

    ref ! SubscribeTransitionCallBack(self) // register for state change events

    refToInstance = refToInstance.updated(ref, instanceId)

    ref
  }

}

object InvokerPool {
  def props(f: (ActorRefFactory, InstanceId) => ActorRef,
            p: (ActivationMessage, InstanceId) => Future[RecordMetadata],
            pc: MessageConsumer) = {
    Props(new InvokerPool(f, p, pc))
  }

  /** A stub identity for invoking the test action. This does not need to be a valid identity. */
  val healthActionIdentity = {
    val whiskSystem = "whisk.system"
    Identity(Subject(whiskSystem), EntityName(whiskSystem), AuthKey(UUID(), Secret()), Set[Privilege]())
  }

  /** An action to use for monitoring invoker health. */
  def healthAction(i: InstanceId) = ExecManifest.runtimesManifest.resolveDefaultRuntime("nodejs:6").map { manifest =>
    new WhiskAction(
      namespace = healthActionIdentity.namespace.toPath,
      name = EntityName(s"invokerHealthTestAction${i.toInt}"),
      exec = CodeExecAsString(manifest, """function main(params) { return params; }""", None))
  }
}

/**
 * Actor representing an Invoker
 *
 * This finite state-machine represents an Invoker in its possible
 * states "Healthy" and "Offline".
 */
class InvokerActor(invokerInstance: InstanceId, controllerInstance: InstanceId) extends FSM[InvokerState, InvokerInfo] {
  implicit val transid = TransactionId.invokerHealth
  implicit val logging = new AkkaLogging(context.system.log)
  val name = s"invoker${invokerInstance.toInt}"

  val healthyTimeout = 10.seconds

  // This is done at this point to not intermingle with the state-machine
  // especially their timeouts.
  def customReceive: Receive = {
    case _: RecordMetadata => // The response of putting testactions to the MessageProducer. We don't have to do anything with them.
  }
  override def receive = customReceive.orElse(super.receive)

  /**
   *  Always start UnHealthy. Then the invoker receives some test activations and becomes Healthy.
   */
  startWith(UnHealthy, InvokerInfo(new RingBuffer[Boolean](InvokerActor.bufferSize)))

  /**
   * An Offline invoker represents an existing but broken
   * invoker. This means, that it does not send pings anymore.
   */
  when(Offline) {
    case Event(_: PingMessage, _) => goto(UnHealthy)
  }

  /**
   * An UnHealthy invoker represents an invoker that was not able to handle actions successfully.
   */
  when(UnHealthy, stateTimeout = healthyTimeout) {
    case Event(_: PingMessage, _) => stay
    case Event(StateTimeout, _)   => goto(Offline)
    case Event(Tick, info) => {
      invokeTestAction()
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

  /**
   * Handle the completion of an Activation in every state.
   */
  whenUnhandled {
    case Event(cm: InvocationFinishedMessage, info) => handleCompletionMessage(cm.successful, info.buffer)
  }

  /** Logging on Transition change */
  onTransition {
    case _ -> Offline =>
      transid.mark(
        this,
        LoggingMarkers.LOADBALANCER_INVOKER_OFFLINE,
        s"$name is offline",
        akka.event.Logging.WarningLevel)
    case _ -> UnHealthy =>
      transid.mark(
        this,
        LoggingMarkers.LOADBALANCER_INVOKER_UNHEALTHY,
        s"$name is unhealthy",
        akka.event.Logging.WarningLevel)
    case _ -> Healthy => logging.info(this, s"$name is healthy")
  }

  /** Scheduler to send test activations when the invoker is unhealthy. */
  onTransition {
    case _ -> UnHealthy => {
      invokeTestAction()
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

    // If the action is successful it seems like the Invoker is Healthy again. So we execute immediately
    // a new test action to remove the errors out of the RingBuffer as fast as possible.
    // The actions that arrive while the invoker is unhealthy are most likely health actions.
    // It is possible they are normal user actions as well. This can happen if such actions were in the
    // invoker queue or in progress while the invoker's status flipped to Unhealthy.
    if (wasActivationSuccessful && stateName == UnHealthy) {
      invokeTestAction()
    }

    // Stay in online if the activations was successful.
    // Stay in offline, if an activeAck reaches the controller.
    if ((stateName == Healthy && wasActivationSuccessful) || stateName == Offline) {
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
   * Creates an activation request with the given action and sends it to the InvokerPool.
   * The InvokerPool redirects it to the invoker which is represented by this InvokerActor.
   */
  private def invokeTestAction() = {
    InvokerPool.healthAction(controllerInstance).map { action =>
      val activationMessage = ActivationMessage(
        // Use the sid of the InvokerSupervisor as tid
        transid = transid,
        action = action.fullyQualifiedName(true),
        // Use empty DocRevision to force the invoker to pull the action from db all the time
        revision = DocRevision.empty,
        user = InvokerPool.healthActionIdentity,
        // Create a new Activation ID for this activation
        activationId = new ActivationIdGenerator {}.make(),
        activationNamespace = action.namespace,
        rootControllerIndex = controllerInstance,
        blocking = false,
        content = None)

      context.parent ! ActivationRequest(activationMessage, invokerInstance)
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
  def props(invokerInstance: InstanceId, controllerInstance: InstanceId) =
    Props(new InvokerActor(invokerInstance, controllerInstance))

  val bufferSize = 10
  val bufferErrorTolerance = 3

  val timerName = "testActionTimer"
}

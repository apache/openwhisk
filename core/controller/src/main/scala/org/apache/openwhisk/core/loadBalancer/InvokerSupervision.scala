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

package org.apache.openwhisk.core.loadBalancer

import java.nio.charset.StandardCharsets

import scala.collection.immutable
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import org.apache.kafka.clients.producer.RecordMetadata
import akka.actor.{Actor, ActorRef, ActorRefFactory, FSM, Props}
import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.pattern.pipe
import akka.util.Timeout
import org.apache.openwhisk.common._
import org.apache.openwhisk.core.connector._
import org.apache.openwhisk.core.database.NoDocumentException
import org.apache.openwhisk.core.entity.ActivationId.ActivationIdGenerator
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.types.EntityStore

// Received events
case object GetStatus

case object Tick

// States an Invoker can be in
sealed trait InvokerState {
  val asString: String
  val isUsable: Boolean
}

object InvokerState {
  // Invokers in this state can be used to schedule workload to
  sealed trait Usable extends InvokerState { val isUsable = true }
  // No workload should be scheduled to invokers in this state
  sealed trait Unusable extends InvokerState { val isUsable = false }

  // A completely healthy invoker, pings arriving fine, no system errors
  case object Healthy extends Usable { val asString = "up" }
  // Pings are arriving fine, the invoker returns system errors though
  case object Unhealthy extends Unusable { val asString = "unhealthy" }
  // Pings are arriving fine, the invoker does not respond with active-acks in the expected time though
  case object Unresponsive extends Unusable { val asString = "unresponsive" }
  // Pings are not arriving for this invoker
  case object Offline extends Unusable { val asString = "down" }
}

// Possible answers of an activation
sealed trait InvocationFinishedResult
object InvocationFinishedResult {
  // The activation could be successfully executed from the system's point of view. That includes user- and application
  // errors
  case object Success extends InvocationFinishedResult
  // The activation could not be executed because of a system error
  case object SystemError extends InvocationFinishedResult
  // The active-ack did not arrive before it timed out
  case object Timeout extends InvocationFinishedResult
}

case class ActivationRequest(msg: ActivationMessage, invoker: InvokerInstanceId)
case class InvocationFinishedMessage(invokerInstance: InvokerInstanceId, result: InvocationFinishedResult)

// Sent to a monitor if the state changed
case class CurrentInvokerPoolState(newState: IndexedSeq[InvokerHealth])

// Data stored in the Invoker
final case class InvokerInfo(buffer: RingBuffer[InvocationFinishedResult])

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
class InvokerPool(childFactory: (ActorRefFactory, InvokerInstanceId) => ActorRef,
                  sendActivationToInvoker: (ActivationMessage, InvokerInstanceId) => Future[RecordMetadata],
                  pingConsumer: MessageConsumer,
                  monitor: Option[ActorRef])
    extends Actor {

  import InvokerState._

  implicit val transid: TransactionId = TransactionId.invokerHealth
  implicit val logging: Logging = new AkkaLogging(context.system.log)
  implicit val timeout: Timeout = Timeout(5.seconds)
  implicit val ec: ExecutionContext = context.dispatcher

  // State of the actor. Mutable vars with immutable collections prevents closures or messages
  // from leaking the state for external mutation
  var instanceToRef = immutable.Map.empty[Int, ActorRef]
  var refToInstance = immutable.Map.empty[ActorRef, InvokerInstanceId]
  var status = IndexedSeq[InvokerHealth]()

  def receive: Receive = {
    case p: PingMessage =>
      val invoker = instanceToRef.getOrElse(p.instance.toInt, registerInvoker(p.instance))
      instanceToRef = instanceToRef.updated(p.instance.toInt, invoker)

      // For the case when the invoker was restarted and got a new displayed name
      val oldHealth = status(p.instance.toInt)
      if (oldHealth.id != p.instance) {
        status = status.updated(p.instance.toInt, new InvokerHealth(p.instance, oldHealth.status))
        refToInstance = refToInstance.updated(invoker, p.instance)
      }

      invoker.forward(p)

    case GetStatus => sender() ! status

    case msg: InvocationFinishedMessage =>
      // Forward message to invoker, if InvokerActor exists
      instanceToRef.get(msg.invokerInstance.toInt).foreach(_.forward(msg))

    case CurrentState(invoker, currentState: InvokerState) =>
      refToInstance.get(invoker).foreach { instance =>
        status = status.updated(instance.toInt, new InvokerHealth(instance, currentState))
      }
      logStatus()

    case Transition(invoker, oldState: InvokerState, newState: InvokerState) =>
      refToInstance.get(invoker).foreach { instance =>
        status = status.updated(instance.toInt, new InvokerHealth(instance, newState))
      }
      logStatus()

    // this is only used for the internal test action which enabled an invoker to become healthy again
    case msg: ActivationRequest => sendActivationToInvoker(msg.msg, msg.invoker).pipeTo(sender)
  }

  def logStatus(): Unit = {
    monitor.foreach(_ ! CurrentInvokerPoolState(status))
    val pretty = status.map(i => s"${i.id.toInt} -> ${i.status}")
    logging.info(this, s"invoker status changed to ${pretty.mkString(", ")}")
  }

  /** Receive Ping messages from invokers. */
  val pingPollDuration: FiniteDuration = 1.second
  val invokerPingFeed: ActorRef = context.system.actorOf(Props {
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
  def padToIndexed[A](list: IndexedSeq[A], n: Int, f: (Int) => A): IndexedSeq[A] = list ++ (list.size until n).map(f)

  // Register a new invoker
  def registerInvoker(instanceId: InvokerInstanceId): ActorRef = {
    logging.info(this, s"registered a new invoker: invoker${instanceId.toInt}")(TransactionId.invokerHealth)

    // Grow the underlying status sequence to the size needed to contain the incoming ping. Dummy values are created
    // to represent invokers, where ping messages haven't arrived yet
    status = padToIndexed(
      status,
      instanceId.toInt + 1,
      i => new InvokerHealth(InvokerInstanceId(i, userMemory = instanceId.userMemory), Offline))
    status = status.updated(instanceId.toInt, new InvokerHealth(instanceId, Offline))

    val ref = childFactory(context, instanceId)
    ref ! SubscribeTransitionCallBack(self) // register for state change events
    refToInstance = refToInstance.updated(ref, instanceId)

    ref
  }

}

object InvokerPool {
  private def createTestActionForInvokerHealth(db: EntityStore, action: WhiskAction): Future[Unit] = {
    implicit val tid: TransactionId = TransactionId.loadbalancer
    implicit val ec: ExecutionContext = db.executionContext
    implicit val logging: Logging = db.logging

    WhiskAction
      .get(db, action.docid)
      .flatMap { oldAction =>
        WhiskAction.put(db, action.revision(oldAction.rev), Some(oldAction))(tid, notifier = None)
      }
      .recover {
        case _: NoDocumentException => WhiskAction.put(db, action, old = None)(tid, notifier = None)
      }
      .map(_ => {})
      .andThen {
        case Success(_) => logging.info(this, "test action for invoker health now exists")
        case Failure(e) => logging.error(this, s"error creating test action for invoker health: $e")
      }
  }

  /**
   * Prepares everything for the health protocol to work (i.e. creates a testaction)
   *
   * @param controllerInstance instance of the controller we run in
   * @param entityStore store to write the action to
   * @return throws an exception on failure to prepare
   */
  def prepare(controllerInstance: ControllerInstanceId, entityStore: EntityStore): Unit = {
    InvokerPool
      .healthAction(controllerInstance)
      .map {
        // Await the creation of the test action; on failure, this will abort the constructor which should
        // in turn abort the startup of the controller.
        a =>
          Await.result(createTestActionForInvokerHealth(entityStore, a), 1.minute)
      }
      .orElse {
        throw new IllegalStateException(
          "cannot create test action for invoker health because runtime manifest is not valid")
      }
  }

  def props(f: (ActorRefFactory, InvokerInstanceId) => ActorRef,
            p: (ActivationMessage, InvokerInstanceId) => Future[RecordMetadata],
            pc: MessageConsumer,
            m: Option[ActorRef] = None): Props = {
    Props(new InvokerPool(f, p, pc, m))
  }

  /** A stub identity for invoking the test action. This does not need to be a valid identity. */
  val healthActionIdentity: Identity = {
    val whiskSystem = "whisk.system"
    val uuid = UUID()
    Identity(Subject(whiskSystem), Namespace(EntityName(whiskSystem), uuid), BasicAuthenticationAuthKey(uuid, Secret()))
  }

  /** An action to use for monitoring invoker health. */
  def healthAction(i: ControllerInstanceId): Option[WhiskAction] =
    ExecManifest.runtimesManifest.resolveDefaultRuntime("nodejs:default").map { manifest =>
      new WhiskAction(
        namespace = healthActionIdentity.namespace.name.toPath,
        name = EntityName(s"invokerHealthTestAction${i.asString}"),
        exec = CodeExecAsString(manifest, """function main(params) { return params; }""", None),
        limits = ActionLimits(memory = MemoryLimit(MemoryLimit.MIN_MEMORY)))
    }
}

/**
 * Actor representing an Invoker
 *
 * This finite state-machine represents an Invoker in its possible
 * states "Healthy" and "Offline".
 */
class InvokerActor(invokerInstance: InvokerInstanceId, controllerInstance: ControllerInstanceId)
    extends FSM[InvokerState, InvokerInfo] {

  import InvokerState._

  implicit val transid: TransactionId = TransactionId.invokerHealth
  implicit val logging: Logging = new AkkaLogging(context.system.log)
  val name = s"invoker${invokerInstance.toInt}"

  val healthyTimeout: FiniteDuration = 10.seconds

  // This is done at this point to not intermingle with the state-machine
  // especially their timeouts.
  def customReceive: Receive = {
    case _: RecordMetadata => // The response of putting testactions to the MessageProducer. We don't have to do anything with them.
  }
  override def receive: Receive = customReceive.orElse(super.receive)

  /** Always start UnHealthy. Then the invoker receives some test activations and becomes Healthy. */
  startWith(Unhealthy, InvokerInfo(new RingBuffer[InvocationFinishedResult](InvokerActor.bufferSize)))

  /** An Offline invoker represents an existing but broken invoker. This means, that it does not send pings anymore. */
  when(Offline) {
    case Event(_: PingMessage, _) => goto(Unhealthy)
  }

  // To be used for all states that should send test actions to reverify the invoker
  val healthPingingState: StateFunction = {
    case Event(_: PingMessage, _) => stay
    case Event(StateTimeout, _)   => goto(Offline)
    case Event(Tick, _) =>
      invokeTestAction()
      stay
  }

  /** An Unhealthy invoker represents an invoker that was not able to handle actions successfully. */
  when(Unhealthy, stateTimeout = healthyTimeout)(healthPingingState)

  /** An Unresponsive invoker represents an invoker that is not responding with active acks in a timely manner */
  when(Unresponsive, stateTimeout = healthyTimeout)(healthPingingState)

  /**
   * A Healthy invoker is characterized by continuously getting pings. It will go offline if that state is not confirmed
   * for 20 seconds.
   */
  when(Healthy, stateTimeout = healthyTimeout) {
    case Event(_: PingMessage, _) => stay
    case Event(StateTimeout, _)   => goto(Offline)
  }

  /** Handle the completion of an Activation in every state. */
  whenUnhandled {
    case Event(cm: InvocationFinishedMessage, info) => handleCompletionMessage(cm.result, info.buffer)
  }

  /** Logging on Transition change */
  onTransition {
    case _ -> newState if !newState.isUsable =>
      transid.mark(
        this,
        LoggingMarkers.LOADBALANCER_INVOKER_STATUS_CHANGE(newState.asString),
        s"$name is ${newState.asString}",
        akka.event.Logging.WarningLevel)
    case _ -> newState if newState.isUsable => logging.info(this, s"$name is ${newState.asString}")
  }

  // To be used for all states that should send test actions to reverify the invoker
  def healthPingingTransitionHandler(state: InvokerState): TransitionHandler = {
    case _ -> `state` =>
      invokeTestAction()
      setTimer(InvokerActor.timerName, Tick, 1.minute, repeat = true)
    case `state` -> _ => cancelTimer(InvokerActor.timerName)
  }

  onTransition(healthPingingTransitionHandler(Unhealthy))
  onTransition(healthPingingTransitionHandler(Unresponsive))

  initialize()

  /**
   * Handling for active acks. This method saves the result (successful or unsuccessful)
   * into an RingBuffer and checks, if the InvokerActor has to be changed to UnHealthy.
   *
   * @param result: result of Activation
   * @param buffer to be used
   */
  private def handleCompletionMessage(result: InvocationFinishedResult,
                                      buffer: RingBuffer[InvocationFinishedResult]) = {
    buffer.add(result)

    // If the action is successful it seems like the Invoker is Healthy again. So we execute immediately
    // a new test action to remove the errors out of the RingBuffer as fast as possible.
    // The actions that arrive while the invoker is unhealthy are most likely health actions.
    // It is possible they are normal user actions as well. This can happen if such actions were in the
    // invoker queue or in progress while the invoker's status flipped to Unhealthy.
    if (result == InvocationFinishedResult.Success && stateName == Unhealthy) {
      invokeTestAction()
    }

    // Stay in online if the activations was successful.
    // Stay in offline, if an activeAck reaches the controller.
    if ((stateName == Healthy && result == InvocationFinishedResult.Success) || stateName == Offline) {
      stay
    } else {
      val entries = buffer.toList
      // Goto Unhealthy or Unresponsive respectively if there are more errors than accepted in buffer, else goto Healthy
      if (entries.count(_ == InvocationFinishedResult.SystemError) > InvokerActor.bufferErrorTolerance) {
        gotoIfNotThere(Unhealthy)
      } else if (entries.count(_ == InvocationFinishedResult.Timeout) > InvokerActor.bufferErrorTolerance) {
        gotoIfNotThere(Unresponsive)
      } else {
        gotoIfNotThere(Healthy)
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
        rootControllerIndex = controllerInstance,
        blocking = false,
        content = None,
        initArgs = Set.empty,
        lockedArgs = Map.empty)

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
  def props(invokerInstance: InvokerInstanceId, controllerInstance: ControllerInstanceId) =
    Props(new InvokerActor(invokerInstance, controllerInstance))

  val bufferSize = 10
  val bufferErrorTolerance = 3

  val timerName = "testActionTimer"
}

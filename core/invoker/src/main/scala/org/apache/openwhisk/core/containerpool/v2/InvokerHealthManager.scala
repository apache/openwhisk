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

package org.apache.openwhisk.core.containerpool.v2

import akka.actor.Status.{Failure => FailureMessage}
import akka.actor.{Actor, ActorRef, ActorRefFactory, ActorSystem, FSM, Props, Stash}
import akka.util.Timeout
import org.apache.openwhisk.common.InvokerState.{Healthy, Offline, Unhealthy}
import org.apache.openwhisk.common._
import org.apache.openwhisk.core.connector._
import org.apache.openwhisk.core.containerpool.ContainerRemoved
import org.apache.openwhisk.core.database.{ArtifactStore, NoDocumentException}
import org.apache.openwhisk.core.entitlement.Privilege
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.entity.types.EntityStore
import org.apache.openwhisk.core.entity.{ActivationResponse => _, _}
import org.apache.openwhisk.core.etcd.EtcdKV.InvokerKeys
import org.apache.openwhisk.core.service.UpdateDataOnChange

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class InvokerHealthManager(instanceId: InvokerInstanceId,
                           healthContainerProxyFactory: (ActorRefFactory, ActorRef) => ActorRef,
                           dataManagementService: ActorRef,
                           entityStore: ArtifactStore[WhiskEntity])(implicit actorSystem: ActorSystem, logging: Logging)
    extends FSM[InvokerState, InvokerHealthData]
    with Stash {

  implicit val requestTimeout = Timeout(5.seconds)
  implicit val ec: ExecutionContext = actorSystem.dispatcher
  implicit val transid: TransactionId = TransactionId.invokerHealth

  private[containerpool] var healthActionProxy: Option[ActorRef] = None

  startWith(
    Offline,
    InvokerInfo(
      new RingBuffer[Boolean](InvokerHealthManager.bufferSize),
      memory = MemoryInfo(instanceId.userMemory.toMB, 0, 0)))

  when(Offline) {
    case Event(GracefulShutdown, _: InvokerInfo) =>
      logging.warn(this, "Received a graceful shutdown flag, stopping the invoker.")
      stay

    case Event(Enable, _) =>
      InvokerHealthManager.prepare(entityStore, instanceId).map { _ =>
        startTestAction(self)
      }
      goto(Unhealthy)
  }

  when(Unhealthy) {
    case Event(ContainerRemoved(_), _) =>
      healthActionProxy = None
      startTestAction(self)
      stay

    case Event(msg: FailureMessage, _) =>
      logging.error(this, s"invoker${instanceId}, status:${stateName} got a failure message: ${msg}")
      stay

    case Event(ContainerCreationFailed(_), _) =>
      stay
  }

  when(Healthy) {
    case Event(msg: FailureMessage, _) =>
      logging.error(this, s"invoker${instanceId}, status:${stateName} got a failure message: ${msg}")
      goto(Unhealthy)
  }

  whenUnhandled {
    case Event(_: Initialized, _) =>
      // Initialized messages sent by ContainerProxy for HealthManger
      stay()

    case Event(ContainerRemoved(_), _) =>
      // Drop messages sent by ContainerProxy for HealthManger
      healthActionProxy = None
      stay()

    case Event(GracefulShutdown, _) =>
      self ! GracefulShutdown
      goto(Offline)

    case Event(healthMsg: HealthMessage, data: InvokerInfo) =>
      if (stateName != Offline) {
        handleHealthMessage(healthMsg.state, data.buffer)
      } else {
        stay
      }

    case Event(memoryInfo: MemoryInfo, data: InvokerInfo) =>
      publishHealthStatusAndStay(stateName, data.copy(memory = memoryInfo))

    // in case of StatusRuntimeException: NOT_FOUND: etcdserver: requested lease not found, we need to get the lease again.
    case Event(t: FailureMessage, _) =>
      logging.error(this, s"Failure happens, restart InvokerHealthManager: ${t}")
      goto(Offline)
  }

  // It is important to note that stateName and the stateData in onTransition callback refer to the previous one.
  // We should access to the next data with nextStateData
  onTransition {
    case Offline -> Unhealthy =>
      publishHealthStatusAndStay(Unhealthy, nextStateData)

    case Healthy -> Unhealthy =>
      unstashAll()
      transid.mark(
        this,
        LoggingMarkers.LOADBALANCER_INVOKER_STATUS_CHANGE(Unhealthy.asString),
        s"invoker${instanceId.toInt} is unhealthy",
        akka.event.Logging.WarningLevel)
      startTestAction(self)
      publishHealthStatusAndStay(Unhealthy, nextStateData)

    case _ -> Healthy =>
      logging.info(this, s"invoker became healthy, stop health action proxy.")
      unstashAll()
      stopTestAction()

      publishHealthStatusAndStay(Healthy, nextStateData)

    case oldState -> newState if oldState != newState =>
      publishHealthStatusAndStay(newState, nextStateData)
      unstashAll()
  }

  private def publishHealthStatusAndStay(state: InvokerState, stateData: InvokerHealthData) = {
    stateData match {
      case data: InvokerInfo =>
        val invokerResourceMessage = InvokerResourceMessage(
          state.asString,
          data.memory.freeMemory,
          data.memory.busyMemory,
          data.memory.inProgressMemory,
          instanceId.tags,
          instanceId.dedicatedNamespaces)
        dataManagementService ! UpdateDataOnChange(InvokerKeys.health(instanceId), invokerResourceMessage.serialize)
        stay using data.copy(currentInvokerResource = Some(invokerResourceMessage))

      case data =>
        logging.error(this, s"unexpected data is found: $data")
        stay
    }
  }

  initialize()

  private def startTestAction(manager: ActorRef): Unit = {
    val namespace = InvokerHealthManager.healthActionIdentity.namespace.name.asString
    val docId = InvokerHealthManager.healthAction(instanceId).get.docid

    WhiskAction.get(entityStore, docId).onComplete {
      case Success(action) =>
        val initialize = Initialize(namespace, action.toExecutableWhiskAction.get, "", 0, transid)
        startHealthAction(initialize, manager)
      case Failure(t) => logging.error(this, s"get health action error: ${t.getMessage}")
    }
  }

  private def startHealthAction(initialize: Initialize, manager: ActorRef): Unit = {
    healthActionProxy match {
      case Some(proxy) =>
        // make healthContainerProxy's status is Running, then healthContainerProxy can fetch the activation using ActivationServiceClient
        proxy ! initialize
      case None =>
        val proxy = healthContainerProxyFactory(context, manager)
        proxy ! initialize
        healthActionProxy = Some(proxy)
    }
  }

  def stopTestAction(): Unit = {
    healthActionProxy.foreach {
      healthActionProxy = None
      _ ! GracefulShutdown
    }
  }

  /**
   * This method is to handle health message from ContainerProxy.pub
   * It can induce status change.
   *
   * @param state  activation result state
   * @param buffer RingBuffer to track status
   * @return
   */
  def handleHealthMessage(state: Boolean, buffer: RingBuffer[Boolean]): State = {
    buffer.add(state)
    val falseStateCount = buffer.toList.count(_ == false)
    if (falseStateCount < InvokerHealthManager.bufferErrorTolerance) {
      gotoIfNotThere(Healthy)
    } else {
      logging.warn(
        this,
        s"become unhealthy because system error exceeded the error tolerance, falseStateCount $falseStateCount, errorTolerance ${InvokerHealthManager.bufferErrorTolerance}")
      gotoIfNotThere(Unhealthy)
    }
  }

  /**
   * This is to decide weather to change from the newState or not.
   * If current state is already newState, it will stay, otherwise it will change its state.
   *
   * @param newState the desired state to change.
   * @return
   */
  private def gotoIfNotThere(newState: InvokerState) = {
    if (stateName == newState) {
      stay()
    } else {
      goto(newState)
    }
  }

  /** Delays all incoming messages until unstashAll() is called */
  def delay = {
    stash()
    stay
  }

}

case class HealthActivationServiceClient() extends Actor {

  private var closed: Boolean = false

  override def receive: Receive = {
    case StartClient => sender() ! ClientCreationCompleted()
    case _: RequestActivation =>
      InvokerHealthManager.healthActivation match {
        case Some(activation) if !closed =>
          sender() ! activation.copy(
            transid = TransactionId.invokerHealthActivation,
            activationId = ActivationId.generate())

        case _ if closed =>
          context.parent ! ClientClosed
          context.stop(self)

        case _ => // do nothing
      }

    case CloseClientProxy =>
      closed = true

  }
}

object InvokerHealthManager {
  val healthActionNamePrefix = "invokerHealthTestAction"
  val bufferSize = 10
  val bufferErrorTolerance = 3
  val healthActionIdentity: Identity = {
    val whiskSystem = "whisk.system"
    val uuid = UUID()
    Identity(
      Subject(whiskSystem),
      Namespace(EntityName(whiskSystem), uuid),
      BasicAuthenticationAuthKey(uuid, Secret()),
      Set[Privilege]())
  }

  def healthAction(i: InvokerInstanceId): Option[WhiskAction] =
    ExecManifest.runtimesManifest.resolveDefaultRuntime("nodejs:default").map { manifest =>
      new WhiskAction(
        namespace = InvokerHealthManager.healthActionIdentity.namespace.name.toPath,
        name = EntityName(s"$healthActionNamePrefix${i.toInt}"),
        exec = CodeExecAsString(manifest, """function main(params) { return params; }""", None),
        limits = ActionLimits(memory = MemoryLimit(MemoryLimit.MIN_MEMORY), logs = LogLimit(0.B)))
    }

  var healthActivation: Option[ActivationMessage] = None

  private def createTestActionForInvokerHealth(db: EntityStore, action: WhiskAction): Future[DocInfo] = {
    implicit val tid: TransactionId = TransactionId.invokerHealthManager
    implicit val ec: ExecutionContext = db.executionContext
    implicit val logging: Logging = db.logging

    WhiskAction
      .get(db, action.docid)
      .flatMap { oldAction =>
        WhiskAction.put(db, action.revision(oldAction.rev), Some(oldAction))(tid, notifier = None)
      }
      .recoverWith {
        case _: NoDocumentException => WhiskAction.put(db, action, old = None)(tid, notifier = None)
      }
      .andThen {
        case Success(_) => logging.info(this, "test action for invoker health now exists")
        case Failure(e) => logging.error(this, s"error creating test action for invoker health: $e")
      }
  }

  private def createHealthActivation(entityStore: ArtifactStore[WhiskEntity],
                                     docInfo: DocInfo)(implicit ec: ExecutionContext, logging: Logging) = {
    implicit val transId = TransactionId.invokerHealth

    WhiskAction.get(entityStore, docInfo.id).onComplete {
      case Success(action) =>
        healthActivation = Some(
          ActivationMessage(
            TransactionId.invokerHealth,
            action.toExecutableWhiskAction.get.fullyQualifiedName(true),
            action.rev,
            healthActionIdentity,
            ActivationId.generate(),
            ControllerInstanceId("health"),
            blocking = false,
            content = None))
      case Failure(t) => logging.error(this, s"get health action error: ${t.getMessage}")
    }
  }

  def prepare(entityStore: ArtifactStore[WhiskEntity],
              invokerInstanceId: InvokerInstanceId)(implicit ec: ExecutionContext, logging: Logging): Future[Unit] = {
    InvokerHealthManager.healthAction(invokerInstanceId) match {
      case Some(action) =>
        createTestActionForInvokerHealth(entityStore, action)
          .map(docId => createHealthActivation(entityStore, docId))
      case None =>
        throw new IllegalStateException(
          "cannot create test action for invoker health because runtime manifest is not valid")
    }
  }

  def props(instanceId: InvokerInstanceId,
            childFactory: (ActorRefFactory, ActorRef) => ActorRef,
            dataManagementService: ActorRef,
            entityStore: ArtifactStore[WhiskEntity])(implicit actorSystem: ActorSystem, logging: Logging): Props = {
    Props(new InvokerHealthManager(instanceId, childFactory, dataManagementService, entityStore))
  }
}

//recevied from ContainerProxy actor
case class HealthMessage(state: Boolean)

//rereived from ContainerPool actor
case class MemoryInfo(freeMemory: Long, busyMemory: Long, inProgressMemory: Long)

// Data stored in the Invoker
sealed class InvokerHealthData

case class InvokerInfo(buffer: RingBuffer[Boolean],
                       memory: MemoryInfo = MemoryInfo(0, 0, 0),
                       currentInvokerResource: Option[InvokerResourceMessage] = None)
    extends InvokerHealthData

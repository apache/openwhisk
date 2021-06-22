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

import java.net.InetSocketAddress
import java.time.Instant

import akka.actor.Status.{Failure => FailureMessage}
import akka.actor.{ActorRef, ActorRefFactory, ActorSystem, FSM, Props, Stash}
import akka.event.Logging.InfoLevel
import akka.io.{IO, Tcp}
import akka.pattern.pipe
import org.apache.openwhisk.common.tracing.WhiskTracerProvider
import org.apache.openwhisk.common.{LoggingMarkers, TransactionId, _}
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.ack.ActiveAck
import org.apache.openwhisk.core.connector.{
  ActivationMessage,
  CombinedCompletionAndResultMessage,
  CompletionMessage,
  ResultMessage
}
import org.apache.openwhisk.core.containerpool._
import org.apache.openwhisk.core.containerpool.logging.LogCollectingException
import org.apache.openwhisk.core.containerpool.v2.FunctionPullingContainerProxy.{
  constructWhiskActivation,
  containerName
}
import org.apache.openwhisk.core.database._
import org.apache.openwhisk.core.entity.ExecManifest.ImageName
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.entity.{ExecutableWhiskAction, ActivationResponse => ExecutionResponse, _}
import org.apache.openwhisk.core.etcd.EtcdKV.ContainerKeys
import org.apache.openwhisk.core.invoker.Invoker.LogsCollector
import org.apache.openwhisk.core.invoker.NamespaceBlacklist
import org.apache.openwhisk.core.scheduler.SchedulerEndpoints
import org.apache.openwhisk.core.service.{RegisterData, UnregisterData}
import org.apache.openwhisk.grpc.RescheduleResponse
import org.apache.openwhisk.http.Messages
import pureconfig.loadConfigOrThrow
import spray.json.DefaultJsonProtocol.{StringJsonFormat, _}
import spray.json._
import pureconfig.generic.auto._

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

// Events used internally
case class RunActivation(action: ExecutableWhiskAction, msg: ActivationMessage)
case class RunActivationCompleted(container: Container, action: ExecutableWhiskAction, duration: Option[Long])
case class InitCodeCompleted(data: WarmData)

// Events received by the actor
case class Initialize(invocationNamespace: String,
                      action: ExecutableWhiskAction,
                      schedulerHost: String,
                      rpcPort: Int,
                      transId: TransactionId)
case class Start(exec: CodeExec[_], memoryLimit: ByteSize, ttl: Option[FiniteDuration] = None)

// Event sent by the actor
case class ContainerCreationFailed(throwable: Throwable)
case class ContainerIsPaused(data: WarmData)
case class ClientCreationFailed(throwable: Throwable,
                                container: Container,
                                invocationNamespace: String,
                                action: ExecutableWhiskAction)
case class ReadyToWork(data: PreWarmData)
case class Initialized(data: InitializedData)
case class Resumed(data: WarmData)
case class ResumeFailed(data: WarmData)
case class RecreateClient(action: ExecutableWhiskAction)

// States
sealed trait ProxyState
case object LeaseStart extends ProxyState
case object Uninitialized extends ProxyState
case object CreatingContainer extends ProxyState
case object ContainerCreated extends ProxyState
case object CreatingClient extends ProxyState
case object ClientCreated extends ProxyState
case object Running extends ProxyState
case object Pausing extends ProxyState
case object Paused extends ProxyState
case object Removing extends ProxyState
case object Rescheduling extends ProxyState

// Errors
case class ContainerHealthErrorWithResumedRun(tid: TransactionId, msg: String, resumeRun: RunActivation)
    extends Exception(msg)

// Data
sealed abstract class Data(val memoryLimit: ByteSize) {
  def getContainer: Option[Container]
}
case class NonexistentData() extends Data(0.B) {
  override def getContainer = None
}
case class MemoryData(override val memoryLimit: ByteSize) extends Data(memoryLimit) {
  override def getContainer = None
}
trait WithClient { val clientProxy: ActorRef }
case class PreWarmData(container: Container,
                       kind: String,
                       override val memoryLimit: ByteSize,
                       expires: Option[Deadline] = None)
    extends Data(memoryLimit) {
  override def getContainer = Some(container)
  def isExpired(): Boolean = expires.exists(_.isOverdue())
}

case class ContainerCreatedData(container: Container, invocationNamespace: String, action: ExecutableWhiskAction)
    extends Data(action.limits.memory.megabytes.MB) {
  override def getContainer = Some(container)
}

case class InitializedData(container: Container,
                           invocationNamespace: String,
                           action: ExecutableWhiskAction,
                           override val clientProxy: ActorRef)
    extends Data(action.limits.memory.megabytes.MB)
    with WithClient {
  override def getContainer = Some(container)
  def toReschedulingData(resumeRun: RunActivation) =
    ReschedulingData(container, invocationNamespace, action, clientProxy, resumeRun)
}

case class WarmData(container: Container,
                    invocationNamespace: String,
                    action: ExecutableWhiskAction,
                    revision: DocRevision,
                    lastUsed: Instant,
                    override val clientProxy: ActorRef)
    extends Data(action.limits.memory.megabytes.MB)
    with WithClient {
  override def getContainer = Some(container)
  def toReschedulingData(resumeRun: RunActivation) =
    ReschedulingData(container, invocationNamespace, action, clientProxy, resumeRun)
}

case class ReschedulingData(container: Container,
                            invocationNamespace: String,
                            action: ExecutableWhiskAction,
                            override val clientProxy: ActorRef,
                            resumeRun: RunActivation)
    extends Data(action.limits.memory.megabytes.MB)
    with WithClient {
  override def getContainer = Some(container)
}

class FunctionPullingContainerProxy(
  factory: (TransactionId,
            String,
            ImageName,
            Boolean,
            ByteSize,
            Int,
            Option[ExecutableWhiskAction]) => Future[Container],
  entityStore: ArtifactStore[WhiskEntity],
  namespaceBlacklist: NamespaceBlacklist,
  get: (ArtifactStore[WhiskEntity], DocId, DocRevision, Boolean) => Future[WhiskAction],
  dataManagementService: ActorRef,
  clientProxyFactory: (ActorRefFactory,
                       String,
                       FullyQualifiedEntityName,
                       DocRevision,
                       String,
                       Int,
                       ContainerId) => ActorRef,
  sendActiveAck: ActiveAck,
  storeActivation: (TransactionId, WhiskActivation, Boolean, UserContext) => Future[Any],
  collectLogs: LogsCollector,
  getLiveContainerCount: (String, FullyQualifiedEntityName, DocRevision) => Future[Long],
  getWarmedContainerLimit: (String) => Future[(Int, FiniteDuration)],
  instance: InvokerInstanceId,
  invokerHealthManager: ActorRef,
  poolConfig: ContainerPoolConfig,
  timeoutConfig: ContainerProxyTimeoutConfig,
  healtCheckConfig: ContainerProxyHealthCheckConfig,
  testTcp: Option[ActorRef])(implicit actorSystem: ActorSystem, logging: Logging)
    extends FSM[ProxyState, Data]
    with Stash {
  startWith(Uninitialized, NonexistentData())

  implicit val ec = actorSystem.dispatcher

  private val UnusedTimeoutName = "UnusedTimeout"
  private val unusedTimeout = timeoutConfig.pauseGrace
  private val IdleTimeoutName = "PausingTimeout"
  private val idleTimeout = timeoutConfig.idleContainer
  private val KeepingTimeoutName = "KeepingTimeout"
  private val RunningActivationTimeoutName = "RunningActivationTimeout"
  private val runningActivationTimeout = 10.seconds

  private var timedOut = false

  var healthPingActor: Option[ActorRef] = None //setup after prewarm starts
  val tcp: ActorRef = testTcp.getOrElse(IO(Tcp)) //allows to testing interaction with Tcp extension

  val runningActivations = new java.util.concurrent.ConcurrentHashMap[String, Boolean]

  when(Uninitialized) {
    // pre warm a container (creates a stem cell container)
    case Event(job: Start, _) =>
      factory(
        TransactionId.invokerWarmup,
        containerName(instance, "prewarm", job.exec.kind),
        job.exec.image,
        job.exec.pull,
        job.memoryLimit,
        poolConfig.cpuShare(job.memoryLimit),
        None)
        .map(container => PreWarmData(container, job.exec.kind, job.memoryLimit, expires = job.ttl.map(_.fromNow)))
        .pipeTo(self)
      goto(CreatingContainer)

    // cold start
    case Event(job: Initialize, _) =>
      factory( // create a new container
        TransactionId.invokerColdstart,
        containerName(instance, job.action.namespace.namespace, job.action.name.asString),
        job.action.exec.image,
        job.action.exec.pull,
        job.action.limits.memory.megabytes.MB,
        poolConfig.cpuShare(job.action.limits.memory.megabytes.MB),
        None)
        .andThen {
          case Failure(t) =>
            context.parent ! ContainerCreationFailed(t)
        }
        .map { container =>
          logging.debug(this, s"a container ${container.containerId} is created for ${job.action}")
          // create a client
          Try(
            clientProxyFactory(
              context,
              job.invocationNamespace,
              job.action.fullyQualifiedName(true),
              job.action.rev,
              job.schedulerHost,
              job.rpcPort,
              container.containerId)) match {
            case Success(clientProxy) =>
              clientProxy ! StartClient
              ContainerCreatedData(container, job.invocationNamespace, job.action)
            case Failure(t) =>
              logging.error(this, s"failed to create activation client caused by: $t")
              ClientCreationFailed(t, container, job.invocationNamespace, job.action)
          }
        }
        .pipeTo(self)

      goto(CreatingClient)

    case _ => delay
  }

  when(CreatingContainer) {
    // container was successfully obtained
    case Event(completed: PreWarmData, _: NonexistentData) =>
      context.parent ! ReadyToWork(completed)
      goto(ContainerCreated) using completed

    // container creation failed
    case Event(t: FailureMessage, _: NonexistentData) =>
      context.parent ! ContainerRemoved(true)
      stop()

    case _ => delay
  }

  // prewarmed state, container created
  when(ContainerCreated) {
    case Event(job: Initialize, data: PreWarmData) =>
      Try(
        clientProxyFactory(
          context,
          job.invocationNamespace,
          job.action.fullyQualifiedName(true),
          job.action.rev,
          job.schedulerHost,
          job.rpcPort,
          data.container.containerId)) match {
        case Success(proxy) =>
          proxy ! StartClient
        case Failure(t) =>
          logging.error(this, s"failed to create activation client for ${job.action} caused by: $t")
          self ! ClientCreationFailed(t, data.container, job.invocationNamespace, job.action)
      }

      goto(CreatingClient) using ContainerCreatedData(data.container, job.invocationNamespace, job.action)

    case Event(Remove, data: PreWarmData) =>
      cleanUp(data.container, None, false)

    // prewarm container failed by health check
    case Event(_: FailureMessage, data: PreWarmData) =>
      MetricEmitter.emitCounterMetric(LoggingMarkers.INVOKER_CONTAINER_HEALTH_FAILED_PREWARM)
      cleanUp(data.container, None)

    case _ => delay
  }

  when(CreatingClient) {
    // wait for client creation when cold start
    case Event(job: ContainerCreatedData, _: NonexistentData) =>
      stay() using job

    // wait for container creation when cold start
    case Event(ClientCreationCompleted(proxy), _: NonexistentData) =>
      self ! ClientCreationCompleted(proxy.orElse(Some(sender())))
      stay()

    // client was successfully obtained
    case Event(ClientCreationCompleted(proxy), data: ContainerCreatedData) =>
      val clientProxy = proxy.getOrElse(sender())
      val fqn = data.action.fullyQualifiedName(true)
      val revision = data.action.rev
      dataManagementService ! RegisterData(
        s"${ContainerKeys.existingContainers(data.invocationNamespace, fqn, revision, Some(instance), Some(data.container.containerId))}",
        "")
      self ! InitializedData(data.container, data.invocationNamespace, data.action, clientProxy)
      goto(ClientCreated)

    // client creation failed
    case Event(t: ClientCreationFailed, _) =>
      invokerHealthManager ! HealthMessage(state = false)
      cleanUp(t.container, t.invocationNamespace, t.action.fullyQualifiedName(withVersion = true), t.action.rev, None)

    // there can be a case that client create is failed and a ClientClosed will be sent by ActivationClientProxy
    // wait for container creation when cold start
    case Event(ClientClosed, _: NonexistentData) =>
      self ! ClientClosed
      stay()

    case Event(ClientClosed, data: ContainerCreatedData) =>
      invokerHealthManager ! HealthMessage(state = false)
      cleanUp(
        data.container,
        data.invocationNamespace,
        data.action.fullyQualifiedName(withVersion = true),
        data.action.rev,
        None)

    // container creation failed when cold start
    case Event(t: FailureMessage, _) =>
      context.parent ! ContainerRemoved(true)
      stop()

    case _ => delay
  }

  // this is for first invocation, once the first invocation is over we are ready to trigger getActivation for action concurrency
  when(ClientCreated) {
    // 1. request activation message to client
    case Event(initializedData: InitializedData, _) =>
      context.parent ! Initialized(initializedData)
      initializedData.clientProxy ! RequestActivation()
      startSingleTimer(UnusedTimeoutName, StateTimeout, unusedTimeout)
      stay() using initializedData

    // 2. read executable action data from db
    case Event(job: ActivationMessage, data: InitializedData) =>
      timedOut = false
      cancelTimer(UnusedTimeoutName)
      handleActivationMessage(job, data.action)
        .pipeTo(self)
      stay() using data

    // 3. request initialize and run command to container
    case Event(job: RunActivation, data: InitializedData) =>
      implicit val transid = job.msg.transid
      logging.debug(this, s"received RunActivation ${job.msg.activationId} for ${job.action} in $stateName")

      initializeAndRunActivation(data.container, data.clientProxy, job.action, job.msg, Some(job))
        .map { activation =>
          RunActivationCompleted(data.container, job.action, activation.duration)
        }
        .pipeTo(self)

      // when it receives InitCodeCompleted, it will move to Running
      stay using data

    case Event(RetryRequestActivation, data: InitializedData) =>
      // if this Container is marked with time out, do not retry
      if (timedOut)
        cleanUp(
          data.container,
          data.invocationNamespace,
          data.action.fullyQualifiedName(withVersion = true),
          data.action.rev,
          Some(data.clientProxy))
      else {
        data.clientProxy ! RequestActivation()
        stay()
      }

    // code initialization was successful
    case Event(completed: InitCodeCompleted, data: InitializedData) =>
      // TODO support concurrency?
      data.clientProxy ! ContainerWarmed // this container is warmed
      1 until completed.data.action.limits.concurrency.maxConcurrent foreach { _ =>
        data.clientProxy ! RequestActivation()
      }

      goto(Running) using completed.data // set warm data

    // ContainerHealthError should cause
    case Event(FailureMessage(e: ContainerHealthErrorWithResumedRun), data: InitializedData) =>
      logging.error(
        this,
        s"container ${data.container.containerId.asString} health check failed on $stateName, ${e.resumeRun.msg.activationId} activation will be rescheduled")
      MetricEmitter.emitCounterMetric(LoggingMarkers.INVOKER_CONTAINER_HEALTH_FAILED_WARM)

      // reschedule message
      data.clientProxy ! RescheduleActivation(
        data.invocationNamespace,
        data.action.fullyQualifiedName(withVersion = true),
        data.action.rev,
        e.resumeRun.msg)

      goto(Rescheduling) using data.toReschedulingData(e.resumeRun)

    // Failed to get activation or execute the action
    case Event(t: FailureMessage, data: InitializedData) =>
      logging.error(
        this,
        s"failed to initialize a container or run an activation for ${data.action} in state: $stateName caused by: $t")
      // Stop containerProxy and ActivationClientProxy both immediately
      cleanUp(
        data.container,
        data.invocationNamespace,
        data.action.fullyQualifiedName(withVersion = true),
        data.action.rev,
        Some(data.clientProxy))

    case Event(StateTimeout, data: InitializedData) =>
      logging.info(this, s"No more activation is coming in state: $stateName, action: ${data.action}")
      // Just mark the ContainerProxy is timedout
      timedOut = true

      stay() // stay here because the ActivationClientProxy may send a new Activation message

    case Event(ClientClosed, data: InitializedData) =>
      logging.error(this, s"The Client closed in state: $stateName, action: ${data.action}")
      // Stop ContainerProxy(ActivationClientProxy will stop also when send ClientClosed to ContainerProxy).
      cleanUp(
        data.container,
        data.invocationNamespace,
        data.action.fullyQualifiedName(withVersion = true),
        data.action.rev,
        None)

    case _ => delay
  }

  when(Rescheduling, stateTimeout = 10.seconds) {

    case Event(res: RescheduleResponse, data: ReschedulingData) =>
      implicit val transId = data.resumeRun.msg.transid
      if (!res.isRescheduled) {
        logging.warn(this, s"failed to reschedule the message ${data.resumeRun.msg.activationId}, clean up data")
        fallbackActivationForReschedulingData(data)
      } else {
        logging.warn(this, s"unhandled message is rescheduled, clean up data")
      }
      cleanUp(
        data.container,
        data.invocationNamespace,
        data.action.fullyQualifiedName(withVersion = true),
        data.action.rev,
        Some(data.clientProxy))

    case Event(StateTimeout, data: ReschedulingData) =>
      logging.error(this, s"Timeout for rescheduling message ${data.resumeRun.msg.activationId}, clean up data")(
        data.resumeRun.msg.transid)

      fallbackActivationForReschedulingData(data)
      cleanUp(
        data.container,
        data.invocationNamespace,
        data.action.fullyQualifiedName(withVersion = true),
        data.action.rev,
        Some(data.clientProxy))
  }

  when(Running) {
    // Run was successful.
    // 1. request activation message to client
    case Event(activationResult: RunActivationCompleted, data: WarmData) =>
      // create timeout
      startSingleTimer(UnusedTimeoutName, StateTimeout, unusedTimeout)
      data.clientProxy ! RequestActivation(activationResult.duration)
      stay() using data

    // 2. read executable action data from db
    case Event(job: ActivationMessage, data: WarmData) =>
      timedOut = false
      cancelTimer(UnusedTimeoutName)
      handleActivationMessage(job, data.action)
        .pipeTo(self)
      stay() using data

    // 3. request run command to container
    case Event(job: RunActivation, data: WarmData) =>
      logging.debug(this, s"received RunActivation ${job.msg.activationId} for ${job.action} in $stateName")
      implicit val transid = job.msg.transid

      initializeAndRunActivation(data.container, data.clientProxy, job.action, job.msg, Some(job))
        .map { activation =>
          RunActivationCompleted(data.container, job.action, activation.duration)
        }
        .pipeTo(self)
      stay using data.copy(lastUsed = Instant.now)

    case Event(RetryRequestActivation, data: WarmData) =>
      // if this Container is marked with time out, do not retry
      if (timedOut) {
        data.container.suspend()(TransactionId.invokerNanny).map(_ => ContainerPaused).pipeTo(self)
        goto(Pausing)
      } else {
        data.clientProxy ! RequestActivation()
        stay()
      }

    case Event(_: ResumeFailed, data: WarmData) =>
      invokerHealthManager ! HealthMessage(state = false)
      cleanUp(
        data.container,
        data.invocationNamespace,
        data.action.fullyQualifiedName(withVersion = true),
        data.action.rev,
        Some(data.clientProxy))

    // ContainerHealthError should cause
    case Event(FailureMessage(e: ContainerHealthError), data: WarmData) =>
      logging.error(this, s"health check failed on $stateName caused by: ContainerHealthError $e")
      MetricEmitter.emitCounterMetric(LoggingMarkers.INVOKER_CONTAINER_HEALTH_FAILED_WARM)
      // Stop containerProxy and ActivationClientProxy both immediately,
      invokerHealthManager ! HealthMessage(state = false)
      cleanUp(
        data.container,
        data.invocationNamespace,
        data.action.fullyQualifiedName(withVersion = true),
        data.action.rev,
        Some(data.clientProxy))

    // ContainerHealthError should cause
    case Event(FailureMessage(e: ContainerHealthErrorWithResumedRun), data: WarmData) =>
      logging.error(
        this,
        s"container ${data.container.containerId.asString} health check failed on $stateName, ${e.resumeRun.msg.activationId} activation will be rescheduled")
      MetricEmitter.emitCounterMetric(LoggingMarkers.INVOKER_CONTAINER_HEALTH_FAILED_WARM)

      // reschedule message
      data.clientProxy ! RescheduleActivation(
        data.invocationNamespace,
        data.action.fullyQualifiedName(withVersion = true),
        data.action.rev,
        e.resumeRun.msg)

      goto(Rescheduling) using data.toReschedulingData(e.resumeRun)

    // Failed to get activation or execute the action
    case Event(t: FailureMessage, data: WarmData) =>
      logging.error(this, s"failed to init or run in state: $stateName caused by: $t")
      // Stop containerProxy and ActivationClientProxy both immediately,
      // and don't send unhealthy state message to the health manager, it's already sent.
      cleanUp(
        data.container,
        data.invocationNamespace,
        data.action.fullyQualifiedName(withVersion = true),
        data.action.rev,
        Some(data.clientProxy))

    case Event(StateTimeout, data: WarmData) =>
      logging.info(
        this,
        s"No more run activation is coming in state: $stateName, action: ${data.action}, container: ${data.container.containerId}")
      // Just mark the ContainerProxy is timedout
      timedOut = true

      stay() // stay here because the ActivationClientProxy may send a new Activation message

    case Event(ClientClosed, data: WarmData) =>
      if (runningActivations.isEmpty) {
        logging.info(this, s"The Client closed in state: $stateName, action: ${data.action}")
        // Stop ContainerProxy(ActivationClientProxy will stop also when send ClientClosed to ContainerProxy).
        cleanUp(
          data.container,
          data.invocationNamespace,
          data.action.fullyQualifiedName(withVersion = true),
          data.action.rev,
          None)
      } else {
        logging.info(
          this,
          s"Remain running activations ${runningActivations.keySet().toString()} when received ClientClosed")
        startSingleTimer(RunningActivationTimeoutName, ClientClosed, runningActivationTimeout)
        stay
      }

    // shutdown the client first and wait for any remaining activation to be executed
    // ContainerProxy will be terminated by StateTimeout if there is no further activation
    case Event(GracefulShutdown, data: WarmData) =>
      logging.info(this, s"receive GracefulShutdown for action: ${data.action}")
      // Just send CloseClientProxy to ActivationClientProxy, make ActivationClientProxy throw ClientClosedException when fetchActivation next time.
      data.clientProxy ! CloseClientProxy
      stay

    case _ => delay
  }

  when(Pausing) {
    case Event(ContainerPaused, data: WarmData) =>
      dataManagementService ! RegisterData(
        ContainerKeys.warmedContainers(
          data.invocationNamespace,
          data.action.fullyQualifiedName(false),
          data.revision,
          instance,
          data.container.containerId),
        "")
      // remove existing key so MemoryQueue can be terminated when timeout
      dataManagementService ! UnregisterData(
        s"${ContainerKeys.existingContainers(data.invocationNamespace, data.action.fullyQualifiedName(true), data.action.rev, Some(instance), Some(data.container.containerId))}")
      context.parent ! ContainerIsPaused(data)
      goto(Paused)

    case Event(_: FailureMessage, data: WarmData) =>
      cleanUp(
        data.container,
        data.invocationNamespace,
        data.action.fullyQualifiedName(false),
        data.action.rev,
        Some(data.clientProxy))

    case _ => delay
  }

  when(Paused) {
    case Event(job: Initialize, data: WarmData) =>
      implicit val transId = job.transId
      val parent = context.parent
      cancelTimer(IdleTimeoutName)
      cancelTimer(KeepingTimeoutName)
      data.container
        .resume()
        .map { _ =>
          logging.info(this, s"Resumed container ${data.container.containerId}")
          // put existing key again
          dataManagementService ! RegisterData(
            s"${ContainerKeys.existingContainers(data.invocationNamespace, data.action.fullyQualifiedName(true), data.action.rev, Some(instance), Some(data.container.containerId))}",
            "")
          parent ! Resumed(data)
          // the new queue may locates on an different scheduler, so recreate the activation client when necessary
          // since akka port will no be used, we can put any value except 0 here
          data.clientProxy ! RequestActivation(
            newScheduler = Some(SchedulerEndpoints(job.schedulerHost, job.rpcPort, 10)))
          startSingleTimer(UnusedTimeoutName, StateTimeout, unusedTimeout)
          timedOut = false
        }
        .recover {
          case t: Throwable =>
            logging.error(this, s"Failed to resume container ${data.container.containerId}, error: $t")
            parent ! ResumeFailed(data)
            self ! ResumeFailed(data)
        }

      // always clean data in etcd regardless of success and failure
      dataManagementService ! UnregisterData(
        ContainerKeys.warmedContainers(
          data.invocationNamespace,
          data.action.fullyQualifiedName(false),
          data.revision,
          instance,
          data.container.containerId))
      goto(Running)

    case Event(StateTimeout, data: WarmData) =>
      (for {
        count <- getLiveContainerCount(data.invocationNamespace, data.action.fullyQualifiedName(false), data.revision)
        (warmedContainerKeepingCount, warmedContainerKeepingTimeout) <- getWarmedContainerLimit(
          data.invocationNamespace)
      } yield {
        logging.info(
          this,
          s"Live container count: ${count}, warmed container keeping count configuration: ${warmedContainerKeepingCount} in namespace: ${data.invocationNamespace}")
        if (count <= warmedContainerKeepingCount) {
          Keep(warmedContainerKeepingTimeout)
        } else {
          Remove
        }
      }).pipeTo(self)
      stay

    case Event(Keep(warmedContainerKeepingTimeout), data: WarmData) =>
      logging.info(
        this,
        s"This is the remaining container for ${data.action}. The container will stop after $warmedContainerKeepingTimeout.")
      startSingleTimer(KeepingTimeoutName, Remove, warmedContainerKeepingTimeout)
      stay

    case Event(Remove | GracefulShutdown, data: WarmData) =>
      dataManagementService ! UnregisterData(
        ContainerKeys.warmedContainers(
          data.invocationNamespace,
          data.action.fullyQualifiedName(false),
          data.revision,
          instance,
          data.container.containerId))
      cleanUp(
        data.container,
        data.invocationNamespace,
        data.action.fullyQualifiedName(false),
        data.action.rev,
        Some(data.clientProxy))

    case _ => delay
  }

  when(Removing, unusedTimeout) {
    // only if ClientProxy is closed, ContainerProxy stops. So it is important for ClientProxy to send ClientClosed.
    case Event(ClientClosed, _) =>
      stop()

    // even if any error occurs, it still waits for ClientClosed event in order to be stopped after the client is closed.
    case Event(t: FailureMessage, _) =>
      logging.error(this, s"unable to delete a container due to ${t}")

      stay

    case Event(StateTimeout, _) =>
      logging.error(this, s"could not receive ClientClosed for ${unusedTimeout}, so just stop the container proxy.")

      stop

    case Event(Remove | GracefulShutdown, _) =>
      stay()
  }

  onTransition {
    case _ -> Uninitialized     => unstashAll()
    case _ -> CreatingContainer => unstashAll()
    case _ -> ContainerCreated =>
      if (healtCheckConfig.enabled) {
        nextStateData.getContainer.foreach { c =>
          logging.info(this, s"enabling health ping for ${c.containerId.asString} on ContainerCreated")
          enableHealthPing(c)
        }
      }
      unstashAll()
    case _ -> CreatingClient => unstashAll()
    case _ -> ClientCreated  => unstashAll()
    case _ -> Running =>
      if (healtCheckConfig.enabled && healthPingActor.isDefined) {
        nextStateData.getContainer.foreach { c =>
          logging.info(this, s"disabling health ping for ${c.containerId.asString} on Running")
          disableHealthPing()
        }
      }
      unstashAll()
    case _ -> Paused   => startSingleTimer(IdleTimeoutName, StateTimeout, idleTimeout)
    case _ -> Removing => unstashAll()
  }

  initialize()

  /** Delays all incoming messages until unstashAll() is called */
  def delay = {
    stash()
    stay
  }

  /**
   * Only change the state if the currentState is not the newState.
   *
   * @param newState of the InvokerActor
   */
  private def gotoIfNotThere(newState: ProxyState) = {
    if (stateName == newState) stay() else goto(newState)
  }

  /**
   * Clean up all meta data of invoking action
   *
   * @param container the container to destroy
   * @param fqn the action to stop
   * @param clientProxy the client to destroy
   * @return
   */
  private def cleanUp(container: Container,
                      invocationNamespace: String,
                      fqn: FullyQualifiedEntityName,
                      revision: DocRevision,
                      clientProxy: Option[ActorRef]): State = {

    dataManagementService ! UnregisterData(
      s"${ContainerKeys.existingContainers(invocationNamespace, fqn, revision, Some(instance), Some(container.containerId))}")

    cleanUp(container, clientProxy)
  }

  private def cleanUp(container: Container, clientProxy: Option[ActorRef], replacePrewarm: Boolean = true): State = {

    context.parent ! ContainerRemoved(replacePrewarm)
    val unpause = stateName match {
      case Paused => container.resume()(TransactionId.invokerNanny)
      case _      => Future.successful(())
    }
    unpause.andThen {
      case Success(_) => destroyContainer(container)
      case Failure(t) =>
        // docker may hang when try to remove a paused container, so we shouldn't remove it
        logging.error(this, s"Failed to resume container ${container.containerId}, error: $t")
    }
    clientProxy match {
      case Some(clientProxy) => clientProxy ! StopClientProxy
      case None              => self ! ClientClosed
    }
    gotoIfNotThere(Removing)
  }

  /**
   * Destroys the container
   *
   * @param container the container to destroy
   */
  private def destroyContainer(container: Container) = {
    container
      .destroy()(TransactionId.invokerNanny)
      .andThen {
        case Failure(t) =>
          logging.error(this, s"Failed to destroy container: ${container.containerId.asString} caused by ${t}")
      }
  }

  private def handleActivationMessage(msg: ActivationMessage, action: ExecutableWhiskAction): Future[RunActivation] = {
    implicit val transid = msg.transid
    logging.info(this, s"received a message ${msg.activationId} for ${msg.action} in $stateName")
    if (!namespaceBlacklist.isBlacklisted(msg.user)) {
      logging.debug(this, s"namespace ${msg.user.namespace.name} is not in the namespaceBlacklist")
      val namespace = msg.action.path
      val name = msg.action.name
      val actionid = FullyQualifiedEntityName(namespace, name).toDocId.asDocInfo(msg.revision)
      val subject = msg.user.subject

      logging.debug(this, s"${actionid.id} $subject ${msg.activationId}")

      // set trace context to continue tracing
      WhiskTracerProvider.tracer.setTraceContext(transid, msg.traceContext)

      // caching is enabled since actions have revision id and an updated
      // action will not hit in the cache due to change in the revision id;
      // if the doc revision is missing, then bypass cache
      if (actionid.rev == DocRevision.empty)
        logging.warn(this, s"revision was not provided for ${actionid.id}")

      get(entityStore, actionid.id, actionid.rev, actionid.rev != DocRevision.empty)
        .flatMap { action =>
          action.toExecutableWhiskAction match {
            case Some(executable) =>
              Future.successful(RunActivation(executable, msg))
            case None =>
              logging
                .error(this, s"non-executable action reached the invoker ${action.fullyQualifiedName(false)}")
              Future.failed(new IllegalStateException("non-executable action reached the invoker"))
          }
        }
        .recoverWith {
          case DocumentRevisionMismatchException(_) =>
            // if revision is mismatched, the action may have been updated,
            // so try again with the latest code
            logging.warn(
              this,
              s"msg ${msg.activationId} for ${msg.action} in $stateName is updated, fetching latest code")
            handleActivationMessage(msg.copy(revision = DocRevision.empty), action)
          case t =>
            // If the action cannot be found, the user has concurrently deleted it,
            // making this an application error. All other errors are considered system
            // errors and should cause the invoker to be considered unhealthy.
            val response = t match {
              case _: NoDocumentException =>
                ExecutionResponse.applicationError(Messages.actionRemovedWhileInvoking)
              case _: DocumentTypeMismatchException | _: DocumentUnreadable =>
                ExecutionResponse.whiskError(Messages.actionMismatchWhileInvoking)
              case e: Throwable =>
                logging.error(this, s"An unknown DB connection error occurred while fetching an action: $e.")
                ExecutionResponse.whiskError(Messages.actionFetchErrorWhileInvoking)
            }
            logging.error(
              this,
              s"Error to fetch action ${msg.action} for msg ${msg.activationId}, error is ${t.getMessage}")

            val context = UserContext(msg.user)
            val activation = generateFallbackActivation(action, msg, response)
            sendActiveAck(
              transid,
              activation,
              msg.blocking,
              msg.rootControllerIndex,
              msg.user.namespace.uuid,
              CombinedCompletionAndResultMessage(transid, activation, instance))
            storeActivation(msg.transid, activation, msg.blocking, context)

            // in case action is removed container proxy should be terminated
            Future.failed(new IllegalStateException("action does not exist"))
        }
    } else {
      // Iff the current namespace is blacklisted, an active-ack is only produced to keep the loadbalancer protocol
      // Due to the protective nature of the blacklist, a database entry is not written.
      val activation =
        generateFallbackActivation(action, msg, ExecutionResponse.applicationError(Messages.namespacesBlacklisted))
      sendActiveAck(
        msg.transid,
        activation,
        false,
        msg.rootControllerIndex,
        msg.user.namespace.uuid,
        CombinedCompletionAndResultMessage(msg.transid, activation, instance))
      logging.warn(
        this,
        s"namespace ${msg.user.namespace.name} was blocked in containerProxy, complete msg ${msg.activationId} with error.")
      Future.failed(new IllegalStateException(s"namespace ${msg.user.namespace.name} was blocked in containerProxy."))
    }

  }

  private def enableHealthPing(c: Container) = {
    val hpa = healthPingActor.getOrElse {
      logging.info(this, s"creating health ping actor for ${c.addr.asString()}")
      val hp = context.actorOf(
        TCPPingClient
          .props(tcp, c.toString(), healtCheckConfig, new InetSocketAddress(c.addr.host, c.addr.port)))
      healthPingActor = Some(hp)
      hp
    }
    hpa ! HealthPingEnabled(true)
  }

  private def disableHealthPing() = {
    healthPingActor.foreach(_ ! HealthPingEnabled(false))
  }

  def fallbackActivationForReschedulingData(data: ReschedulingData): Unit = {
    val context = UserContext(data.resumeRun.msg.user)
    val activation =
      generateFallbackActivation(data.action, data.resumeRun.msg, ExecutionResponse.whiskError(Messages.abnormalRun))

    sendActiveAck(
      data.resumeRun.msg.transid,
      activation,
      data.resumeRun.msg.blocking,
      data.resumeRun.msg.rootControllerIndex,
      data.resumeRun.msg.user.namespace.uuid,
      CombinedCompletionAndResultMessage(data.resumeRun.msg.transid, activation, instance))

    storeActivation(data.resumeRun.msg.transid, activation, data.resumeRun.msg.blocking, context)
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
  private def initializeAndRunActivation(
    container: Container,
    clientProxy: ActorRef,
    action: ExecutableWhiskAction,
    msg: ActivationMessage,
    resumeRun: Option[RunActivation] = None)(implicit tid: TransactionId): Future[WhiskActivation] = {
    // Add the activation to runningActivations set
    runningActivations.put(msg.activationId.asString, true)

    val actionTimeout = action.limits.timeout.duration

    val (env, parameters) = ContainerProxy.partitionArguments(msg.content, msg.initArgs)

    val environment = Map(
      "namespace" -> msg.user.namespace.name.toJson,
      "action_name" -> msg.action.qualifiedNameWithLeadingSlash.toJson,
      "action_version" -> msg.action.version.toJson,
      "activation_id" -> msg.activationId.toString.toJson,
      "transaction_id" -> msg.transid.id.toJson)

    // if the action requests the api key to be injected into the action context, add it here;
    // treat a missing annotation as requesting the api key for backward compatibility
    val authEnvironment = {
      if (action.annotations.isTruthy(Annotations.ProvideApiKeyAnnotationName, valueForNonExistent = true)) {
        msg.user.authkey.toEnvironment.fields
      } else Map.empty
    }

    // Only initialize iff we haven't yet warmed the container
    val initialize = stateData match {
      case _: WarmData =>
        Future.successful(None)
      case _ =>
        val owEnv = (authEnvironment ++ environment ++ Map(
          "deadline" -> (Instant.now.toEpochMilli + actionTimeout.toMillis).toString.toJson)) map {
          case (key, value) => "__OW_" + key.toUpperCase -> value
        }
        container
          .initialize(action.containerInitializer(env ++ owEnv), actionTimeout, action.limits.concurrency.maxConcurrent)
          .map(Some(_))
    }

    val activation: Future[WhiskActivation] = initialize
      .flatMap { initInterval =>
        // immediately setup warmedData for use (before first execution) so that concurrent actions can use it asap
        if (initInterval.isDefined) {
          stateData match {
            case _: InitializedData =>
              self ! InitCodeCompleted(
                WarmData(container, msg.user.namespace.name.asString, action, msg.revision, Instant.now, clientProxy))

            case _ =>
              Future.failed(new IllegalStateException("lease does not exist"))
          }
        }
        val env = authEnvironment ++ environment ++ Map(
          // compute deadline on invoker side avoids discrepancies inside container
          // but potentially under-estimates actual deadline
          "deadline" -> (Instant.now.toEpochMilli + actionTimeout.toMillis).toString.toJson)

        container
          .run(
            parameters,
            env.toJson.asJsObject,
            actionTimeout,
            action.limits.concurrency.maxConcurrent,
            resumeRun.isDefined)(msg.transid)
          .map {
            case (runInterval, response) =>
              val initRunInterval = initInterval
                .map(i => Interval(runInterval.start.minusMillis(i.duration.toMillis), runInterval.end))
                .getOrElse(runInterval)
              constructWhiskActivation(
                action,
                msg,
                initInterval,
                initRunInterval,
                runInterval.duration >= actionTimeout,
                response)
          }
      }
      .recoverWith {
        case h: ContainerHealthError if resumeRun.isDefined =>
          // health error occurs
          logging.error(this, s"caught healthchek check error while running activation")
          Future.failed(ContainerHealthErrorWithResumedRun(h.tid, h.msg, resumeRun.get))

        case InitializationError(interval, response) =>
          Future.successful(
            constructWhiskActivation(
              action,
              msg,
              Some(interval),
              interval,
              interval.duration >= actionTimeout,
              response))

        case t =>
          // Actually, this should never happen - but we want to make sure to not miss a problem
          logging.error(this, s"caught unexpected error while running activation: $t")
          Future.successful(
            constructWhiskActivation(
              action,
              msg,
              None,
              Interval.zero,
              false,
              ExecutionResponse.whiskError(Messages.abnormalRun)))
      }

    val splitAckMessagesPendingLogCollection = collectLogs.logsToBeCollected(action)
    // Sending an active ack is an asynchronous operation. The result is forwarded as soon as
    // possible for blocking activations so that dependent activations can be scheduled. The
    // completion message which frees a load balancer slot is sent after the active ack future
    // completes to ensure proper ordering.
    val sendResult = if (msg.blocking) {
      activation.map { result =>
        val ackMsg =
          if (splitAckMessagesPendingLogCollection) ResultMessage(tid, result)
          else CombinedCompletionAndResultMessage(tid, result, instance)
        sendActiveAck(tid, result, msg.blocking, msg.rootControllerIndex, msg.user.namespace.uuid, ackMsg)
      }
    } else {
      // For non-blocking request, do not forward the result.
      if (splitAckMessagesPendingLogCollection) Future.successful(())
      else
        activation.map { result =>
          val ackMsg = CompletionMessage(tid, result, instance)
          sendActiveAck(tid, result, msg.blocking, msg.rootControllerIndex, msg.user.namespace.uuid, ackMsg)
        }
    }

    activation.foreach { activation =>
      val healthMessage = HealthMessage(!activation.response.isWhiskError)
      invokerHealthManager ! healthMessage
    }

    val context = UserContext(msg.user)

    // Adds logs to the raw activation.
    val activationWithLogs: Future[Either[ActivationLogReadingError, WhiskActivation]] = activation
      .flatMap { activation =>
        // Skips log collection entirely, if the limit is set to 0
        if (action.limits.logs.asMegaBytes == 0.MB) {
          Future.successful(Right(activation))
        } else {
          val start = tid.started(this, LoggingMarkers.INVOKER_COLLECT_LOGS, logLevel = InfoLevel)
          collectLogs(tid, msg.user, activation, container, action)
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
        if (splitAckMessagesPendingLogCollection) {
          sendResult.onComplete(
            _ =>
              sendActiveAck(
                tid,
                activation,
                msg.blocking,
                msg.rootControllerIndex,
                msg.user.namespace.uuid,
                CompletionMessage(tid, activation, instance)))
        }

        // Storing the record. Entirely asynchronous and not waited upon.
        storeActivation(tid, activation, msg.blocking, context)
      }

    // Disambiguate activation errors and transform the Either into a failed/successful Future respectively.
    activationWithLogs
      .andThen {
        // remove activationId from runningActivations in any case
        case _ => runningActivations.remove(msg.activationId.asString)
      }
      .flatMap {
        case Right(act) if !act.response.isSuccess && !act.response.isApplicationError =>
          Future.failed(ActivationUnsuccessfulError(act))
        case Left(error) => Future.failed(error)
        case Right(act)  => Future.successful(act)
      }
  }

  /** Generates an activation with zero runtime. Usually used for error cases */
  private def generateFallbackActivation(action: ExecutableWhiskAction,
                                         msg: ActivationMessage,
                                         response: ExecutionResponse): WhiskActivation = {
    val now = Instant.now
    val causedBy = if (msg.causedBySequence) {
      Some(Parameters(WhiskActivation.causedByAnnotation, JsString(Exec.SEQUENCE)))
    } else None

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
          Parameters(WhiskActivation.kindAnnotation, JsString(action.exec.kind)) ++
          causedBy
      })
  }

}

object FunctionPullingContainerProxy {

  def props(factory: (TransactionId,
                      String,
                      ImageName,
                      Boolean,
                      ByteSize,
                      Int,
                      Option[ExecutableWhiskAction]) => Future[Container],
            entityStore: ArtifactStore[WhiskEntity],
            namespaceBlacklist: NamespaceBlacklist,
            get: (ArtifactStore[WhiskEntity], DocId, DocRevision, Boolean) => Future[WhiskAction],
            dataManagementService: ActorRef,
            clientProxyFactory: (ActorRefFactory,
                                 String,
                                 FullyQualifiedEntityName,
                                 DocRevision,
                                 String,
                                 Int,
                                 ContainerId) => ActorRef,
            ack: ActiveAck,
            store: (TransactionId, WhiskActivation, Boolean, UserContext) => Future[Any],
            collectLogs: LogsCollector,
            getLiveContainerCount: (String, FullyQualifiedEntityName, DocRevision) => Future[Long],
            getWarmedContainerLimit: (String) => Future[(Int, FiniteDuration)],
            instance: InvokerInstanceId,
            invokerHealthManager: ActorRef,
            poolConfig: ContainerPoolConfig,
            timeoutConfig: ContainerProxyTimeoutConfig,
            healthCheckConfig: ContainerProxyHealthCheckConfig =
              loadConfigOrThrow[ContainerProxyHealthCheckConfig](ConfigKeys.containerProxyHealth),
            tcp: Option[ActorRef] = None)(implicit actorSystem: ActorSystem, logging: Logging) =
    Props(
      new FunctionPullingContainerProxy(
        factory,
        entityStore,
        namespaceBlacklist,
        get,
        dataManagementService,
        clientProxyFactory,
        ack,
        store,
        collectLogs,
        getLiveContainerCount,
        getWarmedContainerLimit,
        instance,
        invokerHealthManager,
        poolConfig,
        timeoutConfig,
        healthCheckConfig,
        tcp))

  private val containerCount = new Counter

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
  def constructWhiskActivation(action: ExecutableWhiskAction,
                               msg: ActivationMessage,
                               initInterval: Option[Interval],
                               totalInterval: Interval,
                               isTimeout: Boolean,
                               response: ExecutionResponse) = {

    val causedBy = if (msg.causedBySequence) {
      Some(Parameters(WhiskActivation.causedByAnnotation, JsString(Exec.SEQUENCE)))
    } else None

    val waitTime = {
      val end = initInterval.map(_.start).getOrElse(totalInterval.start)
      Parameters(WhiskActivation.waitTimeAnnotation, Interval(msg.transid.meta.start, end).duration.toMillis.toJson)
    }

    val initTime = {
      initInterval.map(initTime => Parameters(WhiskActivation.initTimeAnnotation, initTime.duration.toMillis.toJson))
    }

    val binding =
      msg.action.binding.map(f => Parameters(WhiskActivation.bindingAnnotation, JsString(f.asString)))

    WhiskActivation(
      activationId = msg.activationId,
      namespace = msg.user.namespace.name.toPath,
      subject = msg.user.subject,
      cause = msg.cause,
      name = action.name,
      version = action.version,
      start = totalInterval.start,
      end = totalInterval.end,
      duration = Some(totalInterval.duration.toMillis),
      response = response,
      annotations = {
        Parameters(WhiskActivation.limitsAnnotation, action.limits.toJson) ++
          Parameters(WhiskActivation.pathAnnotation, JsString(action.fullyQualifiedName(false).asString)) ++
          Parameters(WhiskActivation.kindAnnotation, JsString(action.exec.kind)) ++
          Parameters(WhiskActivation.timeoutAnnotation, JsBoolean(isTimeout)) ++
          causedBy ++ initTime ++ waitTime ++ binding
      })
  }

}

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

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{Actor, ActorRef, ActorRefFactory, Cancellable, Props}
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.openwhisk.common._
import org.apache.openwhisk.core.connector.ContainerCreationError._
import org.apache.openwhisk.core.connector.{
  ContainerCreationAckMessage,
  ContainerCreationMessage,
  ContainerDeletionMessage
}
import org.apache.openwhisk.core.containerpool.{
  AdjustPrewarmedContainer,
  BlackboxStartupError,
  ColdStartKey,
  ContainerPool,
  ContainerPoolConfig,
  ContainerRemoved,
  PrewarmingConfig,
  WhiskContainerStartupError
}
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.http.Messages

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Random, Try}
import scala.collection.immutable.Queue

case class CreationContainer(creationMessage: ContainerCreationMessage, action: WhiskAction)
case class DeletionContainer(deletionMessage: ContainerDeletionMessage)
case object Remove
case class Keep(timeout: FiniteDuration)
case class PrewarmContainer(maxConcurrent: Int)

/**
 * A pool managing containers to run actions on.
 *
 * This pool fulfills the other half of the ContainerProxy contract. Only
 * one job (either Start or Run) is sent to a child-actor at any given
 * time. The pool then waits for a response of that container, indicating
 * the container is done with the job. Only then will the pool send another
 * request to that container.
 *
 * Upon actor creation, the pool will start to prewarm containers according
 * to the provided prewarmConfig, iff set. Those containers will **not** be
 * part of the poolsize calculation, which is capped by the poolSize parameter.
 * Prewarm containers are only used, if they have matching arguments
 * (kind, memory) and there is space in the pool.
 *
 * @param childFactory method to create new container proxy actor
 * @param prewarmConfig optional settings for container prewarming
 * @param poolConfig config for the ContainerPool
 */
class FunctionPullingContainerPool(
  childFactory: ActorRefFactory => ActorRef,
  invokerHealthService: ActorRef,
  poolConfig: ContainerPoolConfig,
  instance: InvokerInstanceId,
  prewarmConfig: List[PrewarmingConfig] = List.empty,
  sendAckToScheduler: (SchedulerInstanceId, ContainerCreationAckMessage) => Future[RecordMetadata])(
  implicit val logging: Logging)
    extends Actor {
  import ContainerPoolV2.memoryConsumptionOf

  implicit val ec = context.system.dispatcher

  private var busyPool = immutable.Map.empty[ActorRef, Data]
  private var inProgressPool = immutable.Map.empty[ActorRef, Data]
  private var warmedPool = immutable.Map.empty[ActorRef, WarmData]
  private var prewarmedPool = immutable.Map.empty[ActorRef, PreWarmData]
  private var prewarmStartingPool = immutable.Map.empty[ActorRef, (String, ByteSize)]

  private var shuttingDown = false

  private val creationMessages = TrieMap[ActorRef, ContainerCreationMessage]()

  private var preWarmScheduler: Option[Cancellable] = None
  private var prewarmConfigQueue = Queue.empty[(CodeExec[_], ByteSize, Option[FiniteDuration])]
  private val prewarmCreateFailedCount = new AtomicInteger(0)

  val logScheduler = context.system.scheduler.scheduleAtFixedRate(0.seconds, 1.seconds)(() => {
    MetricEmitter.emitHistogramMetric(
      LoggingMarkers.INVOKER_CONTAINERPOOL_MEMORY("inprogress"),
      memoryConsumptionOf(inProgressPool))
    MetricEmitter
      .emitHistogramMetric(LoggingMarkers.INVOKER_CONTAINERPOOL_MEMORY("busy"), memoryConsumptionOf(busyPool))
    MetricEmitter
      .emitHistogramMetric(LoggingMarkers.INVOKER_CONTAINERPOOL_MEMORY("prewarmed"), memoryConsumptionOf(prewarmedPool))
    MetricEmitter.emitHistogramMetric(LoggingMarkers.INVOKER_CONTAINERPOOL_MEMORY("max"), poolConfig.userMemory.toMB)
  })

  // Key is ColdStartKey, value is the number of cold Start in minute
  var coldStartCount = immutable.Map.empty[ColdStartKey, Int]

  adjustPrewarmedContainer(true, false)

  // check periodically, adjust prewarmed container(delete if unused for some time and create some increment containers)
  // add some random amount to this schedule to avoid a herd of container removal + creation
  val interval = poolConfig.prewarmExpirationCheckInterval + poolConfig.prewarmExpirationCheckIntervalVariance
    .map(v =>
      Random
        .nextInt(v.toSeconds.toInt))
    .getOrElse(0)
    .seconds

  if (prewarmConfig.exists(!_.reactive.isEmpty)) {
    context.system.scheduler.scheduleAtFixedRate(
      poolConfig.prewarmExpirationCheckInitDelay,
      interval,
      self,
      AdjustPrewarmedContainer)
  }

  val resourceSubmitter = context.system.scheduler.scheduleAtFixedRate(0.seconds, poolConfig.memorySyncInterval)(() => {
    syncMemoryInfo
  })

  private def logContainerStart(c: ContainerCreationMessage, action: WhiskAction, containerState: String): Unit = {
    val FQN = c.action
    if (FQN.namespace.name == "whisk.system" && FQN.fullPath.segments > 2) {
      MetricEmitter.emitCounterMetric(LoggingMarkers.INVOKER_SHAREDPACKAGE(FQN.fullPath.asString))
    }

    MetricEmitter.emitCounterMetric(
      LoggingMarkers.INVOKER_CONTAINER_START(
        containerState,
        c.invocationNamespace,
        c.action.namespace.toString,
        c.action.name.toString))
  }

  def receive: Receive = {
    case PrewarmContainer(maxConcurrent) =>
      if (prewarmConfigQueue.isEmpty) {
        preWarmScheduler.map(_.cancel())
        preWarmScheduler = None
      } else {
        for (_ <- 1 to maxConcurrent if !prewarmConfigQueue.isEmpty) {
          val ((codeExec, byteSize, ttl), newQueue) = prewarmConfigQueue.dequeue
          prewarmConfigQueue = newQueue
          prewarmContainer(codeExec, byteSize, ttl)
        }
      }

    case CreationContainer(create: ContainerCreationMessage, action: WhiskAction) =>
      if (shuttingDown) {
        val message =
          s"creationId: ${create.creationId}, invoker is shutting down, reschedule ${action.fullyQualifiedName(false)}"
        val ack = ContainerCreationAckMessage(
          create.transid,
          create.creationId,
          create.invocationNamespace,
          create.action,
          create.revision,
          create.whiskActionMetaData,
          instance,
          create.schedulerHost,
          create.rpcPort,
          create.retryCount,
          Some(ShuttingDownError),
          Some(message))
        logging.warn(this, message)
        sendAckToScheduler(create.rootSchedulerIndex, ack)
      } else {
        logging.info(this, s"received a container creation message: ${create.creationId}")
        action.toExecutableWhiskAction match {
          case Some(executable) =>
            val createdContainer =
              takeWarmedContainer(executable, create.invocationNamespace, create.revision)
                .map(container => (container, "warmed"))
                .orElse {
                  takeContainer(executable)
                }
            handleChosenContainer(create, executable, createdContainer)
          case None =>
            val message =
              s"creationId: ${create.creationId}, non-executable action reached the container pool ${action.fullyQualifiedName(false)}"
            logging.error(this, message)
            val ack = ContainerCreationAckMessage(
              create.transid,
              create.creationId,
              create.invocationNamespace,
              create.action,
              create.revision,
              create.whiskActionMetaData,
              instance,
              create.schedulerHost,
              create.rpcPort,
              create.retryCount,
              Some(NonExecutableActionError),
              Some(message))
            sendAckToScheduler(create.rootSchedulerIndex, ack)
        }
      }

    case DeletionContainer(deletionMessage: ContainerDeletionMessage) =>
      val oldRevision = deletionMessage.revision
      val invocationNamespace = deletionMessage.invocationNamespace
      val fqn = deletionMessage.action.copy(version = None)

      warmedPool.foreach(warmed => {
        val proxy = warmed._1
        val data = warmed._2

        if (data.invocationNamespace == invocationNamespace
            && data.action.fullyQualifiedName(withVersion = false) == fqn.copy(version = None)
            && data.revision <= oldRevision) {
          proxy ! GracefulShutdown
        }
      })

      busyPool.foreach(f = busy => {
        val proxy = busy._1
        busy._2 match {
          case warmData: WarmData
              if warmData.invocationNamespace == invocationNamespace
                && warmData.action.fullyQualifiedName(withVersion = false) == fqn.copy(version = None)
                && warmData.revision <= oldRevision =>
            proxy ! GracefulShutdown
          case initializedData: InitializedData
              if initializedData.invocationNamespace == invocationNamespace
                && initializedData.action.fullyQualifiedName(withVersion = false) == fqn.copy(version = None) =>
            proxy ! GracefulShutdown
          case _ => // Other actions are ignored.
        }
      })

    case ReadyToWork(data) =>
      prewarmStartingPool = prewarmStartingPool - sender()
      prewarmedPool = prewarmedPool + (sender() -> data)
      // after create prewarm successfully, reset the value to 0
      if (prewarmCreateFailedCount.get() > 0) {
        prewarmCreateFailedCount.set(0)
      }

    // Container is initialized
    case Initialized(data) =>
      busyPool = busyPool + (sender() -> data)
      inProgressPool = inProgressPool - sender()
      // container init completed, send creationAck(success) to scheduler
      creationMessages.remove(sender()).foreach { msg =>
        val ack = ContainerCreationAckMessage(
          msg.transid,
          msg.creationId,
          msg.invocationNamespace,
          msg.action,
          msg.revision,
          msg.whiskActionMetaData,
          instance,
          msg.schedulerHost,
          msg.rpcPort,
          msg.retryCount)
        sendAckToScheduler(msg.rootSchedulerIndex, ack)
      }

    case Resumed(data) =>
      busyPool = busyPool + (sender() -> data)
      inProgressPool = inProgressPool - sender()
      // container init completed, send creationAck(success) to scheduler
      creationMessages.remove(sender()).foreach { msg =>
        val ack = ContainerCreationAckMessage(
          msg.transid,
          msg.creationId,
          msg.invocationNamespace,
          msg.action,
          msg.revision,
          msg.whiskActionMetaData,
          instance,
          msg.schedulerHost,
          msg.rpcPort,
          msg.retryCount)
        sendAckToScheduler(msg.rootSchedulerIndex, ack)
      }

    // if warmed containers is failed to resume, we should try to use other container or create a new one
    case ResumeFailed(data) =>
      inProgressPool = inProgressPool - sender()
      creationMessages.remove(sender()).foreach { msg =>
        val container = takeWarmedContainer(data.action, data.invocationNamespace, data.revision)
          .map(container => (container, "warmed"))
          .orElse {
            takeContainer(data.action)
          }
        handleChosenContainer(msg, data.action, container)
      }

    case ContainerCreationFailed(t) =>
      val (error, message) = t match {
        case WhiskContainerStartupError(msg) => (WhiskError, msg)
        case BlackboxStartupError(msg)       => (BlackBoxError, msg)
        case _                               => (WhiskError, Messages.resourceProvisionError)
      }
      creationMessages.remove(sender()).foreach { msg =>
        val ack = ContainerCreationAckMessage(
          msg.transid,
          msg.creationId,
          msg.invocationNamespace,
          msg.action,
          msg.revision,
          msg.whiskActionMetaData,
          instance,
          msg.schedulerHost,
          msg.rpcPort,
          msg.retryCount,
          Some(error),
          Some(message))
        sendAckToScheduler(msg.rootSchedulerIndex, ack)
      }

    case ContainerIsPaused(data) =>
      warmedPool = warmedPool + (sender() -> data)
      busyPool = busyPool - sender() // remove container from busy pool

    // Container got removed
    case ContainerRemoved(replacePrewarm) =>
      inProgressPool.get(sender()).foreach { _ =>
        inProgressPool = inProgressPool - sender()
      }

      warmedPool.get(sender()).foreach { _ =>
        warmedPool = warmedPool - sender()
      }

      // container was busy (busy indicates at full capacity), so there is capacity to accept another job request
      busyPool.get(sender()).foreach { _ =>
        busyPool = busyPool - sender()
      }

      //in case this was a prewarm
      prewarmedPool.get(sender()).foreach { data =>
        prewarmedPool = prewarmedPool - sender()
        logging.info(
          this,
          s"${if (replacePrewarm) "failed" else "expired"} prewarm [kind: ${data.kind}, memory: ${data.memoryLimit.toString}] removed")
      }

      //in case this was a starting prewarm
      prewarmStartingPool.get(sender()).foreach { data =>
        logging.info(this, s"failed starting prewarm [kind: ${data._1}, memory: ${data._2.toString}] removed")
        prewarmStartingPool = prewarmStartingPool - sender()
        prewarmCreateFailedCount.incrementAndGet()
      }

      //backfill prewarms on every ContainerRemoved, just in case
      if (replacePrewarm) {
        adjustPrewarmedContainer(false, false) //in case a prewarm is removed due to health failure or crash
      }

      // there maybe a chance that container create failed or init grpc client failed,
      // send creationAck(reschedule) to scheduler
      creationMessages.remove(sender()).foreach { msg =>
        val ack = ContainerCreationAckMessage(
          msg.transid,
          msg.creationId,
          msg.invocationNamespace,
          msg.action,
          msg.revision,
          msg.whiskActionMetaData,
          instance,
          msg.schedulerHost,
          msg.rpcPort,
          msg.retryCount,
          Some(UnknownError),
          Some("ContainerProxy init failed."))
        sendAckToScheduler(msg.rootSchedulerIndex, ack)
      }

    case GracefulShutdown =>
      shuttingDown = true
      waitForPoolToClear()

    case Enable =>
      shuttingDown = false

    case AdjustPrewarmedContainer =>
      // Reset the prewarmCreateCount value when do expiration check and backfill prewarm if possible
      prewarmCreateFailedCount.set(0)
      adjustPrewarmedContainer(false, true)
  }

  /** Install prewarm containers up to the configured requirements for each kind/memory combination or specified kind/memory */
  private def adjustPrewarmedContainer(init: Boolean, scheduled: Boolean): Unit = {
    if (!shuttingDown) {
      if (scheduled) {
        //on scheduled time, remove expired prewarms
        ContainerPoolV2.removeExpired(poolConfig, prewarmConfig, prewarmedPool).foreach { p =>
          prewarmedPool = prewarmedPool - p
          p ! Remove
        }
        //on scheduled time, emit cold start counter metric with memory + kind
        coldStartCount foreach { coldStart =>
          val coldStartKey = coldStart._1
          MetricEmitter.emitCounterMetric(
            LoggingMarkers.CONTAINER_POOL_PREWARM_COLDSTART(coldStartKey.memory.toString, coldStartKey.kind))
        }
      }

      ContainerPoolV2
        .increasePrewarms(
          init,
          scheduled,
          coldStartCount,
          prewarmConfig,
          prewarmedPool,
          prewarmStartingPool,
          prewarmConfigQueue)
        .foreach { c =>
          val config = c._1
          val currentCount = c._2._1
          val desiredCount = c._2._2
          if (prewarmCreateFailedCount.get() > poolConfig.prewarmMaxRetryLimit) {
            logging.warn(
              this,
              s"[kind: ${config.exec.kind}, memory: ${config.memoryLimit.toString}] prewarm create failed count exceeds max retry limit: ${poolConfig.prewarmMaxRetryLimit}, currentCount: ${currentCount}, desiredCount: ${desiredCount}")
          } else {
            if (currentCount < desiredCount) {
              (currentCount until desiredCount).foreach { _ =>
                poolConfig.prewarmContainerCreationConfig match {
                  case Some(_) =>
                    prewarmConfigQueue =
                      prewarmConfigQueue.enqueue((config.exec, config.memoryLimit, config.reactive.map(_.ttl)))
                  case None =>
                    prewarmContainer(config.exec, config.memoryLimit, config.reactive.map(_.ttl))
                }
              }
            }
          }
        }

      // run queue consumer
      poolConfig.prewarmContainerCreationConfig.foreach(config => {
        logging.info(
          this,
          s"prewarm container creation is starting with creation delay configuration [maxConcurrent: ${config.maxConcurrent}, creationDelay: ${config.creationDelay.toMillis} millisecond]")
        if (preWarmScheduler.isEmpty) {
          preWarmScheduler = Some(
            context.system.scheduler
              .scheduleAtFixedRate(0.seconds, config.creationDelay, self, PrewarmContainer(config.maxConcurrent)))
        }
      })

      if (scheduled) {
        //   lastly, clear coldStartCounts each time scheduled event is processed to reset counts
        coldStartCount = immutable.Map.empty[ColdStartKey, Int]
      }
    }
  }

  private def syncMemoryInfo: Unit = {
    val busyMemory = memoryConsumptionOf(busyPool)
    val inProgressMemory = memoryConsumptionOf(inProgressPool)
    invokerHealthService ! MemoryInfo(
      poolConfig.userMemory.toMB - busyMemory - inProgressMemory,
      busyMemory,
      inProgressMemory)
  }

  /** Creates a new container and updates state accordingly. */
  private def createContainer(memoryLimit: ByteSize): (ActorRef, Data) = {
    val ref = childFactory(context)
    val data = MemoryData(memoryLimit)
    ref -> data
  }

  /** Creates a new prewarmed container */
  private def prewarmContainer(exec: CodeExec[_], memoryLimit: ByteSize, ttl: Option[FiniteDuration]): Unit = {
    val newContainer = childFactory(context)
    prewarmStartingPool = prewarmStartingPool + (newContainer -> (exec.kind, memoryLimit))
    newContainer ! Start(exec, memoryLimit, ttl)
  }

  /** this is only for cold start statistics of prewarm configs, e.g. not blackbox or other configs. */
  def incrementColdStartCount(kind: String, memoryLimit: ByteSize): Unit = {
    prewarmConfig
      .filter { config =>
        kind == config.exec.kind && memoryLimit == config.memoryLimit
      }
      .foreach { _ =>
        val coldStartKey = ColdStartKey(kind, memoryLimit)
        coldStartCount.get(coldStartKey) match {
          case Some(value) => coldStartCount = coldStartCount + (coldStartKey -> (value + 1))
          case None        => coldStartCount = coldStartCount + (coldStartKey -> 1)
        }
      }
  }

  /**
   * Takes a warmed container out of the warmed pool
   * iff a container with a matching revision is found
   *
   * @param action the action.
   * @param invocationNamespace the invocation namespace for shared package.
   * @param revision the DocRevision.
   * @return the container iff found
   */
  private def takeWarmedContainer(action: ExecutableWhiskAction,
                                  invocationNamespace: String,
                                  revision: DocRevision): Option[(ActorRef, Data)] = {
    warmedPool
      .find {
        case (_, WarmData(_, `invocationNamespace`, `action`, `revision`, _, _)) => true
        case _                                                                   => false
      }
      .map {
        case (ref, data) =>
          warmedPool = warmedPool - ref
          logging.info(this, s"Choose warmed container ${data.container.containerId}")
          (ref, data)
      }
  }

  /**
   * Takes a prewarm container out of the prewarmed pool
   * iff a container with a matching kind and suitable memory is found.
   *
   * @param action the action that holds the kind and the required memory.
   * @param maximumMemory the maximum memory container can have
   * @return the container iff found
   */
  private def takePrewarmContainer(action: ExecutableWhiskAction, maximumMemory: ByteSize): Option[(ActorRef, Data)] = {
    val kind = action.exec.kind
    val memory = action.limits.memory.megabytes.MB

    // find a container with same kind and smallest memory
    prewarmedPool.filter {
      case (_, PreWarmData(_, `kind`, preMemory, _)) if preMemory >= memory && preMemory <= maximumMemory => true
      case _                                                                                              => false
    }.toList match {
      case Nil =>
        None
      case res =>
        val (ref, data) = res.minBy(_._2.memoryLimit)
        prewarmedPool = prewarmedPool - ref

        //get the appropriate ttl from prewarm configs
        val ttl =
          prewarmConfig.find(pc => pc.memoryLimit == memory && pc.exec.kind == kind).flatMap(_.reactive.map(_.ttl))
        prewarmContainer(action.exec, data.memoryLimit, ttl)
        Some(ref, data)
    }
  }

  /** Removes a container and updates state accordingly. */
  def removeContainer(toDelete: ActorRef) = {
    toDelete ! Remove
    warmedPool = warmedPool - toDelete
  }

  /**
   * Calculate if there is enough free memory within a given pool.
   *
   * @param pool The pool, that has to be checked, if there is enough free memory.
   * @param memory The amount of memory to check.
   * @return true, if there is enough space for the given amount of memory.
   */
  private def hasPoolSpaceFor[A](pool: Map[A, Data], memory: ByteSize): Boolean = {
    memoryConsumptionOf(pool) + memory.toMB <= poolConfig.userMemory.toMB
  }

  /**
   * Make all busyPool's memoryQueue actor shutdown gracefully
   */
  private def waitForPoolToClear(): Unit = {
    busyPool.keys.foreach(_ ! GracefulShutdown)
    warmedPool.keys.foreach(_ ! GracefulShutdown)
    if (inProgressPool.nonEmpty) {
      context.system.scheduler.scheduleOnce(5.seconds) {
        waitForPoolToClear()
      }
    }
  }

  /**
   * take a prewarmed container or create a new one
   *
   * @param executable The executable whisk action
   * @return
   */
  private def takeContainer(executable: ExecutableWhiskAction) = {
    val freeMemory = poolConfig.userMemory.toMB - memoryConsumptionOf(busyPool ++ warmedPool ++ inProgressPool)
    val deletableMemory = memoryConsumptionOf(warmedPool)
    val requiredMemory = executable.limits.memory.megabytes
    if (requiredMemory > freeMemory + deletableMemory) {
      None
    } else {
      // try to take a preWarmed container whose memory doesn't exceed the max `usable` memory
      takePrewarmContainer(
        executable,
        if (poolConfig.prewarmPromotion) (freeMemory + deletableMemory).MB else requiredMemory.MB) match {
        // there is a suitable preWarmed container but not enough free memory for it, delete some warmed container first
        case Some(container) if container._2.memoryLimit > freeMemory.MB =>
          ContainerPoolV2
            .remove(warmedPool, container._2.memoryLimit - freeMemory.MB)
            .map(removeContainer)
            .headOption
            .map { _ =>
              (container, "prewarmed")
            }
        // there is a suitable preWarmed container and enough free memory for it
        case Some(container) =>
          Some((container, "prewarmed"))
        // there is no suitable preWarmed container and not enough free memory for the action
        case None if executable.limits.memory.megabytes > freeMemory =>
          ContainerPoolV2
            .remove(warmedPool, executable.limits.memory.megabytes.MB - freeMemory.MB)
            .map(removeContainer)
            .headOption
            .map { _ =>
              incrementColdStartCount(executable.exec.kind, executable.limits.memory.megabytes.MB)
              (createContainer(executable.limits.memory.megabytes.MB), "cold")
            }
        // there is no suitable preWarmed container and enough free memory
        case None =>
          incrementColdStartCount(executable.exec.kind, executable.limits.memory.megabytes.MB)
          Some(createContainer(executable.limits.memory.megabytes.MB), "cold")

        // this should not happen, but just for safety
        case _ =>
          None
      }
    }
  }

  private def handleChosenContainer(create: ContainerCreationMessage,
                                    executable: ExecutableWhiskAction,
                                    container: Option[((ActorRef, Data), String)]) = {
    container match {
      case Some(((proxy, data), containerState)) =>
        // record creationMessage so when container created failed, we can send failed message to scheduler
        creationMessages.getOrElseUpdate(proxy, create)
        proxy ! Initialize(create.invocationNamespace, executable, create.schedulerHost, create.rpcPort, create.transid)
        inProgressPool = inProgressPool + (proxy -> data)
        logContainerStart(create, executable.toWhiskAction, containerState)

      case None =>
        val message =
          s"creationId: ${create.creationId}, invoker[$instance] doesn't have enough resource for container: ${create.action}"
        logging.info(this, message)
        syncMemoryInfo
        val ack = ContainerCreationAckMessage(
          create.transid,
          create.creationId,
          create.invocationNamespace,
          create.action,
          create.revision,
          create.whiskActionMetaData,
          instance,
          create.schedulerHost,
          create.rpcPort,
          create.retryCount,
          Some(ResourceNotEnoughError),
          Some(message))
        sendAckToScheduler(create.rootSchedulerIndex, ack)
    }
  }
}

object ContainerPoolV2 {

  /**
   * Calculate the memory of a given pool.
   *
   * @param pool The pool with the containers.
   * @return The memory consumption of all containers in the pool in Megabytes.
   */
  protected[containerpool] def memoryConsumptionOf[A](pool: Map[A, Data]): Long = {
    pool.map(_._2.memoryLimit.toMB).sum
  }

  /**
   * Finds the oldest previously used container to remove to make space for the job passed to run.
   * Depending on the space that has to be allocated, several containers might be removed.
   *
   * NOTE: This method is never called to remove an action that is in the pool already,
   * since this would be picked up earlier in the scheduler and the container reused.
   *
   * @param pool a map of all free containers in the pool
   * @param memory the amount of memory that has to be freed up
   * @return a list of containers to be removed iff found
   */
  @tailrec
  protected[containerpool] def remove[A](pool: Map[A, WarmData],
                                         memory: ByteSize,
                                         toRemove: List[A] = List.empty): List[A] = {
    if (memory > 0.B && pool.nonEmpty && memoryConsumptionOf(pool) >= memory.toMB) {
      // Remove the oldest container if:
      // - there is more memory required
      // - there are still containers that can be removed
      // - there are enough free containers that can be removed
      val (ref, data) = pool.minBy(_._2.lastUsed)
      // Catch exception if remaining memory will be negative
      val remainingMemory = Try(memory - data.memoryLimit).getOrElse(0.B)
      remove(pool - ref, remainingMemory, toRemove ++ List(ref))
    } else {
      // If this is the first call: All containers are in use currently, or there is more memory needed than
      // containers can be removed.
      // Or, if this is one of the recursions: Enough containers are found to get the memory, that is
      // necessary. -> Abort recursion
      toRemove
    }
  }

  /**
   * Find the expired actor in prewarmedPool
   *
   * @param poolConfig
   * @param prewarmConfig
   * @param prewarmedPool
   * @param logging
   * @return a list of expired actor
   */
  def removeExpired[A](poolConfig: ContainerPoolConfig,
                       prewarmConfig: List[PrewarmingConfig],
                       prewarmedPool: Map[A, PreWarmData])(implicit logging: Logging): List[A] = {
    val now = Deadline.now
    val expireds = prewarmConfig
      .flatMap { config =>
        val kind = config.exec.kind
        val memory = config.memoryLimit
        config.reactive
          .map { c =>
            val expiredPrewarmedContainer = prewarmedPool.toSeq
              .filter { warmInfo =>
                warmInfo match {
                  case (_, p @ PreWarmData(_, `kind`, `memory`, _)) if p.isExpired() => true
                  case _                                                             => false
                }
              }
              .sortBy(_._2.expires.getOrElse(now))

            // emit expired container counter metric with memory + kind
            MetricEmitter.emitCounterMetric(LoggingMarkers.CONTAINER_POOL_PREWARM_EXPIRED(memory.toString, kind))
            if (expiredPrewarmedContainer.nonEmpty) {
              logging.info(
                this,
                s"[kind: ${kind} memory: ${memory.toString}] ${expiredPrewarmedContainer.size} expired prewarmed containers")
            }
            expiredPrewarmedContainer.map(e => (e._1, e._2.expires.getOrElse(now)))
          }
          .getOrElse(List.empty)
      }
      .sortBy(_._2) //need to sort these so that if the results are limited, we take the oldest
      .map(_._1)
    if (expireds.nonEmpty) {
      logging.info(this, s"removing up to ${poolConfig.prewarmExpirationLimit} of ${expireds.size} expired containers")
      expireds.take(poolConfig.prewarmExpirationLimit).foreach { e =>
        prewarmedPool.get(e).map { d =>
          logging.info(this, s"removing expired prewarm of kind ${d.kind} with container ${d.container} ")
        }
      }
    }
    expireds.take(poolConfig.prewarmExpirationLimit)
  }

  /**
   * Find the increased number for the prewarmed kind
   *
   * @param init
   * @param scheduled
   * @param coldStartCount
   * @param prewarmConfig
   * @param prewarmedPool
   * @param prewarmStartingPool
   * @param logging
   * @return the current number and increased number for the kind in the Map
   */
  def increasePrewarms(init: Boolean,
                       scheduled: Boolean,
                       coldStartCount: Map[ColdStartKey, Int],
                       prewarmConfig: List[PrewarmingConfig],
                       prewarmedPool: Map[ActorRef, PreWarmData],
                       prewarmStartingPool: Map[ActorRef, (String, ByteSize)],
                       prewarmQueue: Queue[(CodeExec[_], ByteSize, Option[FiniteDuration])])(
    implicit logging: Logging): Map[PrewarmingConfig, (Int, Int)] = {
    prewarmConfig.map { config =>
      val kind = config.exec.kind
      val memory = config.memoryLimit

      val runningCount = prewarmedPool.count {
        // done starting (include expired, since they may not have been removed yet)
        case (_, p @ PreWarmData(_, `kind`, `memory`, _)) => true
        // started but not finished starting (or expired)
        case _ => false
      }
      val startingCount = prewarmStartingPool.count(p => p._2._1 == kind && p._2._2 == memory)
      val queuingCount = prewarmQueue.count(p => p._1.kind == kind && p._2 == memory)
      val currentCount = runningCount + startingCount + queuingCount

      // determine how many are needed
      val desiredCount: Int =
        if (init) config.initialCount
        else {
          if (scheduled) {
            // scheduled/reactive config backfill
            config.reactive
              .map(c => ContainerPool.getReactiveCold(coldStartCount, c, kind, memory).getOrElse(c.minCount)) //reactive -> desired is either cold start driven, or minCount
              .getOrElse(config.initialCount) //not reactive -> desired is always initial count
          } else {
            // normal backfill after removal - make sure at least minCount or initialCount is started
            config.reactive.map(_.minCount).getOrElse(config.initialCount)
          }
        }

      if (currentCount < desiredCount) {
        logging.info(
          this,
          s"found ${currentCount} started and ${startingCount} starting and ${queuingCount} queuing; ${if (init) "initing"
          else "backfilling"} ${desiredCount - currentCount} pre-warms to desired count: ${desiredCount} for kind:${config.exec.kind} mem:${config.memoryLimit.toString}")(
          TransactionId.invokerWarmup)
      }
      (config, (currentCount, desiredCount))
    }.toMap
  }

  def props(factory: ActorRefFactory => ActorRef,
            invokerHealthService: ActorRef,
            poolConfig: ContainerPoolConfig,
            instance: InvokerInstanceId,
            prewarmConfig: List[PrewarmingConfig] = List.empty,
            sendAckToScheduler: (SchedulerInstanceId, ContainerCreationAckMessage) => Future[RecordMetadata])(
    implicit logging: Logging): Props = {
    Props(
      new FunctionPullingContainerPool(
        factory,
        invokerHealthService,
        poolConfig,
        instance,
        prewarmConfig,
        sendAckToScheduler))
  }
}

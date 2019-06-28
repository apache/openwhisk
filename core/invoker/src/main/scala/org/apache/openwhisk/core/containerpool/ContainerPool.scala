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

import akka.actor.{Actor, ActorRef, ActorRefFactory, Props}
import java.time.Instant
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.common.MetricEmitter
import org.apache.openwhisk.common.{AkkaLogging, LoggingMarkers, TransactionId}
import org.apache.openwhisk.core.connector.MessageFeed
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size._
import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.duration._
import scala.util.Try

sealed trait WorkerState
case object Busy extends WorkerState
case object Free extends WorkerState

case class WorkerData(data: ContainerData, state: WorkerState)

case object InitPrewarms
case object ResourceUpdate
case object EmitMetrics

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
 * @param feed actor to request more work from
 * @param prewarmConfig optional settings for container prewarming
 * @param poolConfig config for the ContainerPool
 * @param resMgrFactory factory for ContainerResourceManager impl
 */
class ContainerPool(instanceId: InvokerInstanceId,
                    childFactory: ActorRefFactory => ActorRef,
                    feed: ActorRef,
                    prewarmConfig: List[PrewarmingConfig] = List.empty,
                    poolConfig: ContainerPoolConfig,
                    resMgrFactory: (ActorRef) => ContainerResourceManager)
    extends Actor {
  import ContainerPool.memoryConsumptionOf

  implicit val logging = new AkkaLogging(context.system.log)
  implicit val ec = context.dispatcher

  val resourceManager = resMgrFactory(self)

  var freePool = immutable.Map.empty[ActorRef, ContainerData]
  var busyPool = immutable.Map.empty[ActorRef, ContainerData]
  var prewarmedPool = immutable.Map.empty[ActorRef, ContainerData]
  // If all memory slots are occupied and if there is currently no container to be removed, than the actions will be
  // buffered here to keep order of computation.
  // Otherwise actions with small memory-limits could block actions with large memory limits.
  var runBuffer = immutable.Queue.empty[Run]
  //periodically emit metrics (don't need to do this for each message!)
  context.system.scheduler.schedule(30.seconds, 10.seconds, self, EmitMetrics)
  var resent = immutable.Set.empty[ActivationId]
  val logMessageInterval = 10.seconds

  var prewarmsInitialized = false

  def initPrewarms() = { //invoked via InitPrewarms message
    prewarmConfig.foreach { config =>
      logging.info(this, s"pre-warming ${config.count} ${config.exec.kind} ${config.memoryLimit.toString}")(
        TransactionId.invokerWarmup)
      (1 to config.count).foreach { _ =>
        prewarmContainer(config.exec, config.memoryLimit)
      }
    }
  }

  def inUse = freePool.filter(_._2.activeActivationCount > 0) ++ busyPool
  def logContainerStart(r: Run, containerState: String, activeActivations: Int, container: Option[Container]): Unit = {
    val namespaceName = r.msg.user.namespace.name
    val actionName = r.action.name.name
    val maxConcurrent = r.action.limits.concurrency.maxConcurrent
    val activationId = r.msg.activationId.toString

    r.msg.transid.mark(
      this,
      LoggingMarkers.INVOKER_CONTAINER_START(containerState),
      s"containerStart containerState: $containerState container: $container activations: $activeActivations of max $maxConcurrent action: $actionName namespace: $namespaceName activationId: $activationId ${resourceManager
        .activationStartLogMessage()}",
      akka.event.Logging.InfoLevel)
  }

  def receive: Receive = {
    // A job to run on a container
    //
    // Run messages are received either via the feed or from child containers which cannot process
    // their requests and send them back to the pool for rescheduling (this may happen if "docker" operations
    // fail for example, or a container has aged and was destroying itself when a new request was assigned)
    case r: Run =>
      implicit val tid: TransactionId = r.msg.transid
      resent = resent - r.msg.activationId
      // Check if the message is resent from the buffer. Only the first message on the buffer can be resent.
      val isResentFromBuffer = runBuffer.nonEmpty && runBuffer.dequeueOption.exists(_._1.msg == r.msg)
      // Only process request, if there are no other requests waiting for free slots, or if the current request is the
      // next request to process
      // It is guaranteed, that only the first message on the buffer is resent.
      if (runBuffer.isEmpty || isResentFromBuffer) {
        val warmContainer = ContainerPool
          .schedule(r.action, r.msg.user.namespace.name, freePool)
        val createdContainer =
          // Is there enough space on the invoker (or the cluster manager) for this action to be executed.
          if (warmContainer.isDefined || hasPoolSpaceFor(poolConfig, busyPool, r.action.limits.memory.megabytes.MB)) {
            // Schedule a job to a warm container
            warmContainer
              .map(container => (container, container._2.initingState)) //warmed, warming, and warmingCold always know their state
              .orElse(
                // There was no warm/warming/warmingCold container. Try to take a prewarm container or a cold container.

                // Is there enough space to create a new container or do other containers have to be removed?
                if (hasPoolSpaceFor(poolConfig, busyPool ++ freePool, r.action.limits.memory.megabytes.MB)) {
                  takePrewarmContainer(r.action)
                    .map(container => (container, "prewarmed"))
                    .orElse {
                      Some(createContainer(r.action.limits.memory.megabytes.MB), "cold")
                    }
                } else None)
              .orElse( // Remove a container and create a new one for the given job
                ContainerPool
                // Only free up the amount, that is really needed to free up
                  .remove(
                    freePool,
                    if (!poolConfig.clusterManagedResources) { //do not allow overprovision when cluster manages resources
                      Math.min(r.action.limits.memory.megabytes, memoryConsumptionOf(freePool)).MB
                    } else {
                      r.action.limits.memory.megabytes.MB
                    },
                    Instant.now().minusSeconds(poolConfig.clusterManagedIdleGrace.toSeconds))
                  .map(a => removeContainer(a._1))
                  // If the list had at least one entry, enough containers were removed to start the new container. After
                  // removing the containers, we are not interested anymore in the containers that have been removed.
                  .headOption
                  .map(_ =>
                    takePrewarmContainer(r.action)
                      .map(container => (container, "recreatedPrewarm"))
                      .getOrElse(createContainer(r.action.limits.memory.megabytes.MB), "recreated"))
                  .orElse { //no removals, request space
                    resourceManager.requestSpace(r.action.limits.memory.megabytes.MB)
                    None
                  })
          } else None

        createdContainer match {
          case Some(((actor, data), containerState)) =>
            //increment active count before storing in pool map
            val newData = data.nextRun(r)
            val container = newData.getContainer

            if (newData.activeActivationCount < 1) {
              logging.error(this, s"invalid activation count < 1 ${newData}")
            }

            //only move to busyPool if max reached
            if (!newData.hasCapacity()) {
              if (r.action.limits.concurrency.maxConcurrent > 1) {
                logging.info(
                  this,
                  s"container ${container} is now busy with ${newData.activeActivationCount} activations")
              }
              busyPool = busyPool + (actor -> newData)
              freePool = freePool - actor
              updateUnused() //in case a previously unused is now in-use
            } else {
              //update freePool to track counts
              freePool = freePool + (actor -> newData)
            }
            // Remove the action that get's executed now from the buffer and execute the next one afterwards.
            if (isResentFromBuffer) {
              // It is guaranteed that the currently executed messages is the head of the queue, if the message comes
              // from the buffer
              val (_, newBuffer) = runBuffer.dequeue
              runBuffer = newBuffer
              runBuffer.dequeueOption.foreach {
                case (run, _) =>
                  resent = resent + run.msg.activationId
                  self ! run
              }
            }
            actor ! r // forwards the run request to the container
            logContainerStart(r, containerState, newData.activeActivationCount, container)
          case None =>
            // this can also happen if createContainer fails to start a new container, or
            // if a job is rescheduled but the container it was allocated to has not yet destroyed itself
            // (and a new container would over commit the pool)
            val isErrorLogged = r.retryLogDeadline.map(_.isOverdue).getOrElse(true)
            val msg = s"Rescheduling Run message, too many message in the pool, " +
              s"freePoolSize: ${freePool.size} containers and ${memoryConsumptionOf(freePool)} MB, " +
              s"busyPoolSize: ${busyPool.size} containers and ${memoryConsumptionOf(busyPool)} MB, " +
              s"maxContainersMemory ${poolConfig.userMemory.toMB} MB, " +
              s"userNamespace: ${r.msg.user.namespace.name}, action: ${r.action}, " +
              s"needed memory: ${r.action.limits.memory.megabytes} MB, " +
              s"waiting messages: ${runBuffer.size}, " +
              resourceManager.rescheduleLogMessage
            MetricEmitter.emitCounterMetric(LoggingMarkers.CONTAINER_POOL_RESCHEDULED_ACTIVATION)
            val retryLogDeadline = if (isErrorLogged) {
              if (poolConfig.clusterManagedResources) {
                logging.warn(this, msg)(r.msg.transid) //retry loop may be common in cluster manager resource case, so use warn level
              } else {
                logging.error(this, msg)(r.msg.transid) //otherwise use error level
              }
              Some(logMessageInterval.fromNow)
            } else {
              r.retryLogDeadline
            }
            if (!isResentFromBuffer) {
              // Add this request to the buffer, as it is not there yet.
              runBuffer = runBuffer.enqueue(Run(r.action, r.msg, retryLogDeadline))
            }
        }
      } else {
        // There are currently actions waiting to be executed before this action gets executed.
        // These waiting actions were not able to free up enough memory.
        runBuffer = runBuffer.enqueue(r)
      }

    // Container is free to take more work
    case NeedWork(warmData: WarmedData) =>
      val oldData = freePool.get(sender()).getOrElse(busyPool(sender()))
      val newData =
        warmData.copy(lastUsed = oldData.lastUsed, activeActivationCount = oldData.activeActivationCount - 1)
      if (newData.activeActivationCount < 0) {
        logging.error(this, s"invalid activation count after warming < 1 ${newData}")
      }
      if (newData.hasCapacity()) {
        //remove from busy pool (may already not be there), put back into free pool (to update activation counts)
        freePool = freePool + (sender() -> newData)
        if (busyPool.contains(sender())) {
          busyPool = busyPool - sender()
          if (newData.action.limits.concurrency.maxConcurrent > 1) {
            logging.info(
              this,
              s"concurrent container ${newData.container} is no longer busy with ${newData.activeActivationCount} activations")
          }
        }
      } else {
        busyPool = busyPool + (sender() -> newData)
        freePool = freePool - sender()
      }
      updateUnused() //in case a previously in-use is now unused
      processBuffer()

    // Container is prewarmed and ready to take work
    case NeedWork(data: PreWarmedData) =>
      //stop tracking via reserved
      resourceManager.releaseReservation(sender())
      prewarmedPool = prewarmedPool + (sender() -> data)

    // Container got removed
    case ContainerRemoved =>
      //stop tracking via reserved (should already be removed, except in case of failure)
      resourceManager.releaseReservation(sender())

      // if container was in free pool, it may have been processing (but under capacity),
      // so there is capacity to accept another job request
      val foundFree: Option[ActorRef] = freePool.get(sender()).map { f =>
        freePool = freePool - sender()
        if (f.activeActivationCount > 0) {
          processBuffer()
        }
        updateUnused()
        sender()
      }
      // container was busy (busy indicates at full capacity), so there is capacity to accept another job request
      val foundBusy = busyPool.get(sender()).map { _ =>
        busyPool = busyPool - sender()
        processBuffer()
        sender()
      }
      //if container was neither free or busy, (should never happen, just being defensive)
      if (foundFree.orElse(foundBusy).isEmpty) {
        processBuffer()
      }

    // This message is received for one of these reasons:
    // 1. Container errored while resuming a warm container, could not process the job, and sent the job back
    // 2. The container aged, is destroying itself, and was assigned a job which it had to send back
    // 3. The container aged and is destroying itself
    // 4. The container is paused, and being removed to make room for new containers
    // Update the free/busy lists but no message is sent to the feed since there is no change in capacity yet
    case RescheduleJob =>
      resourceManager.releaseReservation(sender())
      freePool = freePool - sender()
      busyPool = busyPool - sender()
      updateUnused()
    case InitPrewarms =>
      initPrewarms()
    case ResourceUpdate => //we may have more resources - either process the buffer or request another feed message
      processBuffer()
    case ContainerStarted => //only used for receiving post-start from cold container
      //stop tracking via reserved
      resourceManager.releaseReservation(sender())
    case NeedResources(size: ByteSize) => //this is the inverse of NeedWork
      MetricEmitter.emitCounterMetric(LoggingMarkers.CONTAINER_POOL_RESOURCE_ERROR)
      //we probably are here because resources were not available even though we thought they were,
      //so preemptively request more
      resourceManager.requestSpace(size)
    case ReleaseFree(refs) =>
      logging.info(this, s"pool is trying to release ${refs.size} containers by request of invoker")
      //remove each ref, IFF it is still not in use, and has not been used for the idle grade period
      ContainerPool.findIdlesToRemove(poolConfig.clusterManagedIdleGrace, freePool, refs).foreach(removeContainer)
    case EmitMetrics =>
      emitMetrics()
  }

  /** Buffer processing in cluster managed resources means to send the first item in runBuffer;
   *  In non-clustered case, it means signalling MessageFeed (since runBuffer is processed in tight loop).
   * */
  def processBuffer() = {
    //if next runbuffer item has not already been resent, send it
    runBuffer.dequeueOption match {
      case Some((run, _)) => //run the first from buffer
        //avoid sending dupes
        if (!resent.contains(run.msg.activationId)) {
          resent = resent + run.msg.activationId
          self ! run
        }
      case None => //feed me!
        feed ! MessageFeed.Processed
    }
  }

  /** Creates a new container and updates state accordingly. */
  def createContainer(memoryLimit: ByteSize): (ActorRef, ContainerData) = {
    val ref = childFactory(context)
    val data = MemoryData(memoryLimit)
    //increase the reserved (not yet started container) memory tracker
    resourceManager.addReservation(ref, memoryLimit)
    freePool = freePool + (ref -> data)
    ref -> data
  }

  /** Creates a new prewarmed container */
  def prewarmContainer(exec: CodeExec[_], memoryLimit: ByteSize): Unit = {
    //when using cluster managed resources, we can only create prewarms when allowed by the cluster
    if (hasPoolSpaceFor(poolConfig, freePool, memoryLimit, true)(TransactionId.invokerWarmup)) {
      val ref = childFactory(context)
      ref ! Start(exec, memoryLimit)
      //increase the reserved (not yet started container) memory tracker
      resourceManager.addReservation(ref, memoryLimit)
    } else {
      logging.warn(this, "cannot create additional prewarm")
    }
  }

  /**
   * Takes a prewarm container out of the prewarmed pool
   * iff a container with a matching kind and memory is found.
   *
   * @param action the action that holds the kind and the required memory.
   * @return the container iff found
   */
  def takePrewarmContainer(action: ExecutableWhiskAction): Option[(ActorRef, ContainerData)] = {
    val kind = action.exec.kind
    val memory = action.limits.memory.megabytes.MB
    prewarmedPool
      .find {
        case (_, PreWarmedData(_, `kind`, `memory`, _)) => true
        case _                                          => false
      }
      .map {
        case (ref, data) =>
          // Move the container to the usual pool
          freePool = freePool + (ref -> data)
          prewarmedPool = prewarmedPool - ref
          // Create a new prewarm container
          // NOTE: prewarming ignores the action code in exec, but this is dangerous as the field is accessible to the
          // factory
          prewarmContainer(action.exec, memory)
          (ref, data)
      }
  }

  /** Removes a container and updates state accordingly. */
  def removeContainer(toDelete: ActorRef) = {
    toDelete ! Remove
    freePool = freePool - toDelete
    busyPool = busyPool - toDelete
  }

  /**
   * Calculate if there is enough free memory within a given pool.
   *
   * @param poolConfig The ContainerPoolConfig, which indicates who governs the resource accounting
   * @param pool The pool, that has to be checked, if there is enough free memory.
   * @param memory The amount of memory to check.
   * @param prewarm If true, we are checking on behalf of a stem cell container start
   * @return true, if there is enough space for the given amount of memory.
   */
  def hasPoolSpaceFor[A](poolConfig: ContainerPoolConfig,
                         pool: Map[A, ContainerData],
                         memory: ByteSize,
                         prewarm: Boolean = false)(implicit tid: TransactionId): Boolean = {
    resourceManager.canLaunch(memory, memoryConsumptionOf(pool), poolConfig, prewarm)
  }

  def updateUnused() = {
    //TODO: exclude those not past idle grace
    val unused = freePool.filter(_._2.activeActivationCount == 0)
    resourceManager.updateUnused(unused)
  }

  def emitMetrics() = {
    logging.info(
      this,
      s"metrics invoker (self) has ${runBuffer.size} buffered (${runBuffer.map(_.action.limits.memory.megabytes).sum}MB)")

    MetricEmitter.emitGaugeMetric(LoggingMarkers.CONTAINER_POOL_RUNBUFFER_COUNT, runBuffer.size)
    MetricEmitter.emitGaugeMetric(
      LoggingMarkers.CONTAINER_POOL_RUNBUFFER_SIZE,
      runBuffer.map(_.action.limits.memory.megabytes).sum)
    val containersInUse = inUse
    MetricEmitter.emitGaugeMetric(LoggingMarkers.CONTAINER_POOL_ACTIVE_COUNT, containersInUse.size)
    MetricEmitter.emitGaugeMetric(
      LoggingMarkers.CONTAINER_POOL_ACTIVE_SIZE,
      containersInUse.map(_._2.memoryLimit.toMB).sum)
    MetricEmitter.emitGaugeMetric(LoggingMarkers.CONTAINER_POOL_PREWARM_COUNT, prewarmedPool.size)
    MetricEmitter.emitGaugeMetric(
      LoggingMarkers.CONTAINER_POOL_PREWARM_SIZE,
      prewarmedPool.map(_._2.memoryLimit.toMB).sum)
  }
}

object ContainerPool {

  /**
   * Calculate the memory of a given pool.
   *
   * @param pool The pool with the containers.
   * @return The memory consumption of all containers in the pool in Megabytes.
   */
  protected[containerpool] def memoryConsumptionOf[A](pool: Map[A, ContainerData]): Long = {
    pool.map(_._2.memoryLimit.toMB).sum
  }

  /**
   * Finds the best container for a given job to run on.
   *
   * Selects an arbitrary warm container from the passed pool of idle containers
   * that matches the action and the invocation namespace. The implementation uses
   * matching such that structural equality of action and the invocation namespace
   * is required.
   * Returns None iff no matching container is in the idle pool.
   * Does not consider pre-warmed containers.
   *
   * @param action the action to run
   * @param invocationNamespace the namespace, that wants to run the action
   * @param idles a map of idle containers, awaiting work
   * @return a container if one found
   */
  protected[containerpool] def schedule[A](action: ExecutableWhiskAction,
                                           invocationNamespace: EntityName,
                                           idles: Map[A, ContainerData]): Option[(A, ContainerData)] = {
    idles
      .find {
        case (_, c @ WarmedData(_, `invocationNamespace`, `action`, _, _)) if c.hasCapacity() => true
        case _                                                                                => false
      }
      .orElse {
        idles.find {
          case (_, c @ WarmingData(_, `invocationNamespace`, `action`, _, _)) if c.hasCapacity() => true
          case _                                                                                 => false
        }
      }
      .orElse {
        idles.find {
          case (_, c @ WarmingColdData(`invocationNamespace`, `action`, _, _)) if c.hasCapacity() => true
          case _                                                                                  => false
        }
      }
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
   * @param anyAmount free up any amount up to memory size
   * @param lastUsedMax only consider idles last used before lastUsedMax
   * @return a map of containers->ByteSize to be removed iff found
   */
  @tailrec
  protected[containerpool] def remove[A](pool: Map[A, ContainerData],
                                         memory: ByteSize,
                                         lastUsedMax: Instant,
                                         anyAmount: Boolean = false,
                                         toRemove: Map[A, ByteSize] = Map.empty[A, ByteSize]): Map[A, ByteSize] = {

    // Try to find a Free container that does NOT have any active activations AND is initialized with any OTHER action
    val freeContainers = pool.collect {
      // Only warm containers will be removed. Prewarmed containers will stay always.
      case (ref, w: WarmedData) if w.activeActivationCount == 0 && w.lastUsed.isBefore(lastUsedMax) =>
        ref -> w
    }

    if (memory > 0.B && freeContainers.nonEmpty && (anyAmount || memoryConsumptionOf(freeContainers) >= memory.toMB)) {
      // Remove the oldest container if:
      // - there is more memory required
      // - there are still containers that can be removed
      // - there are enough free containers that can be removed
      val (ref, data) = freeContainers.minBy(_._2.lastUsed)
      // Catch exception if remaining memory will be negative
      val remainingMemory = Try(memory - data.memoryLimit).getOrElse(0.B)
      remove(
        freeContainers - ref,
        remainingMemory,
        lastUsedMax,
        anyAmount,
        toRemove ++ Map(ref -> data.action.limits.memory.megabytes.MB))
    } else {
      // If this is the first call: All containers are in use currently, or there is more memory needed than
      // containers can be removed.
      // Or, if this is one of the recursions: Enough containers are found to get the memory, that is
      // necessary. -> Abort recursion
      toRemove
    }
  }

  def findIdlesToRemove[A](idleGrace: FiniteDuration,
                           freePool: Map[A, ContainerData],
                           refs: Iterable[RemoteContainerRef])(implicit logging: Logging): Set[A] = {
    //find containers to remove, IFF it is still not in use,
    // and has not been used since the removal was requested
    // and has not been used past the idle grace period
    val idleGraceInstant = Instant.now().minusSeconds(idleGrace.toSeconds)

    freePool.foreach { c =>
      println(s"c  ${c._2.getContainer}  ${c._2.lastUsed}  ${idleGraceInstant} ${c._2.lastUsed
        .isBefore(idleGraceInstant)} ${c._2.activeActivationCount}")
    }

    val toRemove = freePool
    //FILTER MATCHING MEMORY USAGE WITH LAST USE BEFORE IDLE GRACE INSTANT
      .filter { f =>
        f._2.activeActivationCount == 0 &&
        f._2.lastUsed.isBefore(idleGraceInstant) &&
        f._2.getContainer.isDefined &&
        refs.exists { r =>
          //ONLY INCLUDE CONTAINERS THAT MATCH THE SPECIFIC CONTAINER REFERENCE
          r.size == f._2.memoryLimit && r.lastUsed == f._2.lastUsed && r.containerAddress == f._2.getContainer.get.addr
        }
      }
    toRemove.foreach(i =>
      logging.info(
        this,
        s"removing idle container ${i._2.getContainer.get} with last use ${i._2.lastUsed} before idle grace at ${idleGraceInstant}"))
    toRemove.keySet
  }

  def props(instanceId: InvokerInstanceId,
            factory: ActorRefFactory => ActorRef,
            poolConfig: ContainerPoolConfig,
            feed: ActorRef,
            resMgrFactory: ActorRef => ContainerResourceManager,
            prewarmConfig: List[PrewarmingConfig] = List.empty) =
    Props(new ContainerPool(instanceId, factory, feed, prewarmConfig, poolConfig, resMgrFactory))
}

/** Contains settings needed to perform container prewarming. */
case class PrewarmingConfig(count: Int, exec: CodeExec[_], memoryLimit: ByteSize)

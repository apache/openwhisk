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
import akka.Done
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.CoordinatedShutdown
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.LWWRegister
import akka.cluster.ddata.LWWRegisterKey
import akka.cluster.ddata.ORSet
import akka.cluster.ddata.ORSetKey
import akka.cluster.ddata.Replicator.Changed
import akka.cluster.ddata.Replicator.Subscribe
import akka.cluster.ddata.Replicator.Unsubscribe
import akka.cluster.ddata.Replicator.Update
import akka.cluster.ddata.Replicator.UpdateFailure
import akka.cluster.ddata.Replicator.UpdateSuccess
import akka.cluster.ddata.Replicator.WriteLocal
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Put
import akka.cluster.pubsub.DistributedPubSubMediator.Send
import akka.management.AkkaManagement
import akka.management.cluster.bootstrap.ClusterBootstrap
import java.time.Instant
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.common.LoggingMarkers
import org.apache.openwhisk.common.MetricEmitter
import org.apache.openwhisk.core.entity.ByteSize
import org.apache.openwhisk.core.entity.InvokerInstanceId
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.utils.NodeStats
import org.apache.openwhisk.utils.NodeStatsUpdate
import scala.collection.immutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try

class AkkaClusterContainerResourceManager(system: ActorSystem,
                                          instanceId: InvokerInstanceId,
                                          poolActor: ActorRef,
                                          poolConfig: ContainerPoolConfig)(implicit logging: Logging)
    extends ContainerResourceManager {

  /** cluster state tracking */
  private var localReservations: Map[ActorRef, Reservation] = Map.empty //this pool's own reservations
  private var remoteReservations: Map[Int, List[Reservation]] = Map.empty //other pool's reservations
  private var localUnused: Map[ActorRef, ContainerData] = Map.empty // this pool's unused containers
  private var remoteUnused
    : Map[Int, List[RemoteContainerRef]] = Map.empty //invoker akka address -> List[RemoteContainerRef]

  var clusterActionHostStats = Map.empty[String, NodeStats] //track the most recent node stats per action host (host that is able to run action containers)
  var clusterActionHostsCount = 0
  var prewarmsInitialized = false
  val clusterPoolData: ActorRef =
    system.actorOf(
      Props(new ContainerPoolClusterData(instanceId, poolActor)),
      ContainerPoolClusterData.clusterPoolActorName(instanceId.toInt))

  //invoker keys
  val InvokerIdsKey = ORSetKey[Int]("invokerIds")
  //my keys
  val myId = instanceId.toInt
  val myReservationsKey = LWWRegisterKey[List[Reservation]]("reservation" + myId)
  val myUnusedKey = LWWRegisterKey[List[RemoteContainerRef]]("unused" + myId)
  //remote keys
  var reservationKeys: immutable.Map[Int, LWWRegisterKey[List[Reservation]]] = Map.empty
  var unusedKeys: immutable.Map[Int, LWWRegisterKey[List[RemoteContainerRef]]] = Map.empty

  //cachedValues
  var idMap: immutable.Set[Int] = Set.empty

  override def activationStartLogMessage(): String =
    s"node stats ${clusterActionHostStats} reserved ${localReservations.size} (of max ${poolConfig.clusterManagedResourceMaxStarts}) containers ${reservedSize}MB"

  override def rescheduleLogMessage() = {
    s"reservations: ${localReservations.size}"
  }

  override def requestSpace(size: ByteSize) = {
    val bufferedSize = size * 10 //request 10x the required space to allow for failures and additional traffic
    //find idles up to this size
    val removable = remoteUnused.map(u => u._2.map(u._1 -> _)).flatten.to[ListBuffer]
    val removed = remove(removable, bufferedSize) //add some buffer so that any accumulating activations will have better chance
    if (removed.nonEmpty) {
      val removing = removed.groupBy(_._1).map(k => k._1 -> k._2.map(_._2)) //group the removables by invoker, will send single message per invoker
      logging.info(this, s"requesting removal of ${removed.size} eligible idle containers ${removing}")
      removing.foreach { r =>
        clusterPoolData ! RequestReleaseFree(r._1, r._2)
        //update unusedPool (we won't ask them to be removed twice)
        remoteUnused = remoteUnused + (r._1 -> remoteUnused(r._1).filterNot(r._2.toSet))

      }
    }

  }

  def reservedSize = localReservations.values.map(_.size.toMB).sum
  def remoteReservedSize = remoteReservations.values.map(_.map(_.size.toMB).sum).sum
  private var clusterResourcesAvailable
    : Boolean = false //track to log whenever there is a switch from cluster resources being available to not being available

  def canLaunch(memory: ByteSize, poolMemory: Long, poolConfig: ContainerPoolConfig, prewarm: Boolean): Boolean = {

    if (allowMoreStarts(poolConfig)) {
      val localRes = localReservations.values.map(_.size) //active local reservations
      val remoteRes = remoteReservations.values.toList.flatten.map(_.size) //remote/stale reservations

      //TODO: consider potential to fit each reservation, then required memory for this action.
      val allRes = localRes ++ remoteRes
      //make sure there is at least one node with unreserved mem > memory
      val canLaunch = clusterHasPotentialMemoryCapacity(memory.toMB, allRes) //consider all reservations blocking till they are removed during NodeStatsUpdate
      //log only when changing value
      if (canLaunch != clusterResourcesAvailable) {
        if (canLaunch) {
          logging.info(this, s"cluster can launch action with ${memory.toMB}MB reserved:${reservedSize}")
        } else {
          logging.warn(this, s"cluster cannot launch action with ${memory.toMB}MB reserved:${reservedSize}")
        }
      }
      clusterResourcesAvailable = canLaunch
      canLaunch
    } else {
      logging.info(this, "throttling container starts")
      false
    }
  }

  /** Return true to indicate there is expectation that there is "room" to launch a task with these memory/cpu/ports specs */
  def clusterHasPotentialMemoryCapacity(memory: Double, reserve: Iterable[ByteSize]): Boolean = {
    //copy AgentStats, then deduct pending tasks
    var availableResources = clusterActionHostStats.toList.sortBy(_._2.mem).toMap //sort by mem to match lowest value
    val inNeedReserved = ListBuffer.empty ++ reserve

    var unmatched = 0

    inNeedReserved.foreach { p =>
      //for each pending find an available offer that fits
      availableResources.find(_._2.mem > p.toMB) match {
        case Some(o) =>
          availableResources = availableResources + (o._1 -> o._2.copy(mem = o._2.mem - p.toMB))
          inNeedReserved -= p
        case None => unmatched += 1
      }
    }
    unmatched == 0 && availableResources.exists(_._2.mem > memory)
  }

  /** reservation adjustments */
  override def addReservation(ref: ActorRef, size: ByteSize): Unit = {
    localReservations = localReservations + (ref -> Reservation(size))
  }
  override def releaseReservation(ref: ActorRef): Unit = {
    localReservations = localReservations - ref
  }
  def allowMoreStarts(config: ContainerPoolConfig): Boolean =
    localReservations.size < config.clusterManagedResourceMaxStarts //only positive reservations affect ability to start

  class ContainerPoolClusterData(instanceId: InvokerInstanceId, containerPool: ActorRef) extends Actor {
    //it is possible to use cluster managed resources, but not run the invoker in the cluster
    //when not using cluster boostrapping, you need to set akka seed node configs
    if (poolConfig.useClusterBootstrap) {
      AkkaManagement(context.system).start()
      ClusterBootstrap(context.system).start()
    }
    implicit val cluster = Cluster(system)
    implicit val ec = context.dispatcher
    val mediator = DistributedPubSub(system).mediator
    val replicator = DistributedData(system).replicator
    implicit val myAddress = DistributedData(system).selfUniqueAddress

    mediator ! Put(self) //allow point to point messaging based on the actor name: use Send(/user/<myname>) to send messages to me in the cluster
    //subscribe to invoker ids changes (need to setup additional keys based on each invoker arriving)
    replicator ! Subscribe(InvokerIdsKey, self)
    //add this invoker to ids list
    replicator ! Update(InvokerIdsKey, ORSet.empty[Int], WriteLocal)(_ :+ (myId))

    logging.info(this, "subscribing to NodeStats updates")
    system.eventStream.subscribe(self, classOf[NodeStatsUpdate])

    //track the recent updates, so that we only send updates after changes (since this is done periodically, not on each data change)
    var lastUnused: List[RemoteContainerRef] = List.empty
    var lastReservations: List[Reservation] = List.empty
    var lastStats: Map[String, NodeStats] = Map.empty

    //schedule updates
    context.system.scheduler.schedule(0.seconds, 1.seconds, self, UpdateData)

    CoordinatedShutdown(context.system)
      .addTask(CoordinatedShutdown.PhaseBeforeClusterShutdown, "akkaClusterContainerResourceManagerCleanup") { () =>
        cleanup()
        Future.successful(Done)
      }
    private def cleanup() = {
      //remove this invoker from ids list
      logging.info(this, s"stopping invoker ${myId}")
      replicator ! Update(InvokerIdsKey, ORSet.empty[Int], WriteLocal)(_.remove(myId))

    }
    override def receive: Receive = {
      case UpdateData =>
        //update this invokers reservations seen by other invokers
        val reservations = localReservations.values.toList
        if (lastReservations != reservations) {
          lastReservations = reservations
          logging.info(
            this,
            s"invoker ${myId} (self) has ${reservations.size} reservations (${reservations.map(_.size.toMB).sum}MB)")
          replicator ! Update(myReservationsKey, LWWRegister[List[Reservation]](myAddress, List.empty), WriteLocal)(
            reg => reg.withValueOf(reservations))
        }
        //update this invokers idles seen by other invokers; including only idles past the idleGrace period
        val idleGraceInstant = Instant.now().minusSeconds(poolConfig.clusterManagedIdleGrace.toSeconds)
        val idles = localUnused
          .filter(f => f._2.getContainer.isDefined && f._2.lastUsed.isBefore(idleGraceInstant)) //defensively exclude unused that don't have a Container defined
          .map(f => RemoteContainerRef(f._2.memoryLimit, f._2.lastUsed, f._2.getContainer.map(_.addr).get))
          .toList
        if (lastUnused != idles) { //may change either because the container was added/removed, OR because lastUsed is updated
          lastUnused = idles
          logging.info(
            this,
            s"invoker ${myId} (self) has ${lastUnused.size} unused (${lastUnused.map(_.size.toMB).sum}MB)")
          replicator ! Update(myUnusedKey, LWWRegister[List[RemoteContainerRef]](myAddress, List.empty), WriteLocal)(
            reg => reg.withValueOf(idles))
        }
      case UpdateSuccess => //nothing (normal behavior)
      case f: UpdateFailure[_] => //log the failure
        logging.error(this, s"failed to update replicated data: $f")
      case RequestReleaseFree(id, refs) =>
        val remotePath = s"/user/${ContainerPoolClusterData.clusterPoolActorName(id)}"
        logging.info(this, s"notifying invoker ${id} to free ${refs.size} containers")
        mediator ! Send(path = remotePath, msg = ReleaseFree(refs), localAffinity = false)
      case NodeStatsUpdate(stats) =>
        logging.info(
          this,
          s"received node stats ${stats} local reservations:${localReservations.size} (${reservedSize}MB) remote reservations: ${remoteReservations
            .map(_._2.size)
            .sum} (${remoteReservedSize}MB)")
        clusterActionHostStats = stats
        if (stats.nonEmpty) {
          MetricEmitter.emitGaugeMetric(LoggingMarkers.CLUSTER_RESOURCES_TOTAL_MEM, stats.values.map(_.mem).sum.toLong)
          MetricEmitter.emitGaugeMetric(LoggingMarkers.CLUSTER_RESOURCES_MAX_MEM, stats.values.maxBy(_.mem).mem.toLong)
        }
        MetricEmitter.emitGaugeMetric(LoggingMarkers.CLUSTER_RESOURCES_NODE_COUNT, stats.size)
        MetricEmitter.emitGaugeMetric(LoggingMarkers.CLUSTER_RESOURCES_IDLES_COUNT, localUnused.size)
        val unusedSize = localUnused.map(_._2.memoryLimit.toMB).sum
        logging.info(this, s"metrics invoker ${myId} (self) has ${localUnused.size} unused (${unusedSize}MB)")
        logging.info(this, s"metrics invoker ${myId} (self) has ${localReservations.size} reserved (${reservedSize}MB)")
        MetricEmitter.emitGaugeMetric(LoggingMarkers.CLUSTER_RESOURCES_IDLES_SIZE, unusedSize)
        MetricEmitter.emitGaugeMetric(LoggingMarkers.CLUSTER_RESOURCES_RESERVED_COUNT, localReservations.size)
        MetricEmitter.emitGaugeMetric(
          LoggingMarkers.CLUSTER_RESOURCES_RESERVED_SIZE,
          localReservations.map(_._2.size.toMB).sum)

        if (stats.nonEmpty && !prewarmsInitialized) { //we assume that when stats are received, we should startup prewarm containers
          prewarmsInitialized = true
          logging.info(this, "initializing prewarmpool after stats recevied")
          //        initPrewarms()
          containerPool ! InitPrewarms
        }

        //only signal updates (to pool or replicator) in case things have changed
        if (lastStats != stats) {
          lastStats = stats
          containerPool ! ResourceUpdate
        }

      case r: ReleaseFree =>
        logging.info(this, s"got releasefree from ${sender()}")
        //forward to the pool
        containerPool.forward(r)
      case c @ Changed(LWWRegisterKey(idStr)) =>
        val resRegex = "reservation(\\d+)".r
        val unusedRegex = "unused(\\d+)".r
        val (id, res) = idStr match {
          case resRegex(remoteId)    => remoteId.toInt -> true
          case unusedRegex(remoteId) => remoteId.toInt -> false
        }
        if (id != myId) {

          if (res) {
            val idKey = LWWRegisterKey[List[Reservation]]("reservation" + id)
            val newValue = c.get(idKey).value
            logging.info(this, s"invoker ${id} has ${newValue.size} reservations (${newValue.map(_.size.toMB).sum}MB)")
            remoteReservations = remoteReservations + (id -> newValue)
          } else {
            val unusedKey = LWWRegisterKey[List[RemoteContainerRef]]("unused" + id)
            val newValue = c.get(unusedKey).value
            logging.info(this, s"invoker ${id} has ${newValue.size} idles (${newValue.map(_.size.toMB).sum}MB)")
            remoteUnused = remoteUnused + (id -> newValue)
          }
        }
      case c @ Changed(InvokerIdsKey) =>
        val newValue = c.get(InvokerIdsKey).elements
        val deleted = reservationKeys.keySet.diff(newValue)
        val added = newValue.diff(reservationKeys.keySet)
        added.foreach { id =>
          if (id != myId) { //skip my own id
            if (!reservationKeys.keySet.contains(id)) {
              val idKey = LWWRegisterKey[List[Reservation]]("reservation" + id)
              val unusedKey = LWWRegisterKey[List[RemoteContainerRef]]("unused" + id)
              logging.info(this, s"adding invoker ${id} to resource tracking")
              reservationKeys = reservationKeys + (id -> idKey)
              unusedKeys = unusedKeys + (id -> unusedKey)
              replicator ! Subscribe(idKey, self)
              replicator ! Subscribe(unusedKey, self)
            } else {
              logging.warn(this, s"invoker ${id} already tracked, will not add")
            }
          }
        }
        deleted.foreach { id =>
          if (reservationKeys.keySet.contains(id)) {
            logging.info(
              this,
              s"removing invoker ${id} (${remoteReservations.get(id).map(_.size)} reservations, ${remoteUnused.get(id).map(_.size)} idles) from resource tracking")
            val idKey = LWWRegisterKey[List[Reservation]]("reservation" + id)
            val unusedKey = LWWRegisterKey[List[RemoteContainerRef]]("unused" + id)
            reservationKeys = reservationKeys - id
            unusedKeys = unusedKeys - id
            replicator ! Unsubscribe(idKey, self)
            replicator ! Unsubscribe(unusedKey, self)
            remoteReservations = remoteReservations + (id -> List.empty)
            remoteUnused = remoteUnused + (id -> List.empty)
          } else {
            logging.warn(this, s"invoker ${id} not tracked, will not remove")
          }
        }
    }
  }
  override def updateUnused(newUnused: Map[ActorRef, ContainerData]): Unit = {
    localUnused = newUnused
  }
  def remove[A](pool: ListBuffer[(A, RemoteContainerRef)], memory: ByteSize): List[(A, RemoteContainerRef)] = {

    if (pool.isEmpty) {
      List.empty
    } else {

      val oldest = pool.minBy(_._2.lastUsed)
      if (memory > 0.B) { //&& memoryConsumptionOf(pool) >= memory.toMB //remove any amount possible
        // Remove the oldest container if:
        // - there is more memory required
        // - there are still containers that can be removed
        // - there are enough free containers that can be removed
        //val (ref, data) = freeContainers.minBy(_._2.lastUsed)
        // Catch exception if remaining memory will be negative
        val remainingMemory = Try(memory - oldest._2.size).getOrElse(0.B)
        List(oldest._1 -> oldest._2) ++ remove(pool - oldest, remainingMemory)
      } else {
        // If this is the first call: All containers are in use currently, or there is more memory needed than
        // containers can be removed.
        // Or, if this is one of the recursions: Enough containers are found to get the memory, that is
        // necessary. -> Abort recursion
        List.empty
      }
    }
  }
}

/**
 * Reservation indicates resources allocated to container, but possibly not launched yet. May be negative for container stop.
 * Pending: Allocated from this point of view, but not yet started/stopped by cluster manager.
 * Scheduled: Started/Stopped by cluster manager, but not yet reflected in NodeStats, so must still be considered when allocating resources.
 * */
case class Reservation(size: ByteSize)
case class RemoteContainerRef(size: ByteSize, lastUsed: Instant, containerAddress: ContainerAddress)
case class RequestReleaseFree(id: Int, remoteContainerRefs: List[RemoteContainerRef])
case class ReleaseFree(remoteContainerRefs: List[RemoteContainerRef])
case object UpdateData

object ContainerPoolClusterData {
  def clusterPoolActorName(instanceId: Int): String = "containerPoolCluster" + instanceId.toInt
}

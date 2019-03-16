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
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
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
import akka.cluster.ddata.Replicator.WriteLocal
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Put
import akka.cluster.pubsub.DistributedPubSubMediator.Send
import java.time.Instant
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.core.entity.ByteSize
import org.apache.openwhisk.core.entity.InvokerInstanceId
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.utils.NodeStats
import org.apache.openwhisk.utils.NodeStatsUpdate
import scala.collection.immutable
import scala.collection.mutable.ListBuffer
import scala.util.Try

class AkkaClusterContainerResourceManager(system: ActorSystem,
                                          instanceId: InvokerInstanceId,
                                          poolActor: ActorRef,
                                          poolConfig: ContainerPoolConfig)(implicit logging: Logging)
    extends ContainerResourceManager {

  /** cluster state tracking */
  private var clusterReservations: Map[ActorRef, Reservation] = Map.empty //this pool's own reservations
  private var remoteReservations: Map[Int, List[Reservation]] = Map.empty //other pool's reservations
  private var unused: Map[ActorRef, ContainerData] = Map.empty // this pool's unused containers
  var clusterActionHostStats = Map.empty[String, NodeStats] //track the most recent node stats per action host (host that is able to run action containers)
  var clusterActionHostsCount = 0
  var prewarmsInitialized = false
  val clusterPoolData: ActorRef =
    system.actorOf(
      Props(new ContainerPoolClusterData(instanceId, poolActor)),
      ContainerPoolClusterData.clusterPoolActorName(instanceId.toInt))
  implicit val cluster = Cluster(system)
  val mediator = DistributedPubSub(system).mediator
  val replicator = DistributedData(system).replicator

  mediator ! Put(clusterPoolData) //allow point to point messaging based on the actor name: use Send(/user/<myname>) to send messages to me in the cluster

  //invoker keys
  val InvokerIdsKey = ORSetKey[Int]("invokerIds")
  //my keys
  val myId = instanceId.toInt
  val myKey = LWWRegisterKey[List[Reservation]]("reservation" + myId)
  val myUnusedKey = LWWRegisterKey[List[RemoteContainerRef]]("unused" + myId)
  //remote keys
  var reservationKeys: immutable.Map[Int, LWWRegisterKey[List[Reservation]]] = Map.empty
  var unusedKeys: immutable.Map[Int, LWWRegisterKey[List[RemoteContainerRef]]] = Map.empty

  //cachedValues
  var unusedPool: Map[Int, List[RemoteContainerRef]] = Map.empty //invoker akka address -> List[RemoteContainerRef]
  var idMap: immutable.Set[Int] = Set.empty
  //subscribe to invoker ids changes (need to setup additional keys based on each invoker arriving)
  replicator ! Subscribe(InvokerIdsKey, clusterPoolData)
  //add this invoker to ids list
  replicator ! Update(InvokerIdsKey, ORSet.empty[Int], WriteLocal)(_ + (myId))

  logging.info(this, "subscribing to NodeStats updates")
  system.eventStream.subscribe(clusterPoolData, classOf[NodeStatsUpdate])

  def activationStartLogMessage(): String =
    s"node stats ${clusterActionHostStats} reserved ${clusterReservations.size} (of max ${poolConfig.clusterManagedResourceMaxStarts}) containers ${reservedSize}MB " +
      s"${reservedStartCount} pending starts ${reservedStopCount} pending stops " +
      s"${scheduledStartCount} scheduled starts ${scheduledStopCount} scheduled stops"

  def rescheduleLogMessage() = {
    s"reservations: ${clusterReservations.size}"
  }

  def requestSpace(size: ByteSize) = {
    val bufferedSize = size + 4096.MB
    logging.info(this, s"signalling cluster release of idle resources up to ${bufferedSize.toMB}MB")
    //find idles up to this size
    val removable = unusedPool.map(u => u._2.map(u._1 -> _)).flatten.to[ListBuffer]
    val removed = remove(removable, bufferedSize) //add some buffer so that any accumulating activations will have better chance
    val removing = removed.groupBy(_._1).map(k => k._1 -> k._2.map(_._2)) //group the removables by invoker, will send single message per invoker
    logging.info(this, s"requesting removal of ${removed.size} eligible idle containers ${removing}")
    removing.foreach { r =>
      //        val addrOpt = idMap.find(_.id == r._1).map(_.address)

      val remotePath = s"/user/${ContainerPoolClusterData.clusterPoolActorName(r._1)}"
      logging.info(this, s"notifying invoker ${remotePath}")
      mediator ! Send(path = remotePath, msg = ReleaseFree(r._2), localAffinity = false)

      //update unusedPool (we won't ask them to be removed twice)
      unusedPool = unusedPool + (r._1 -> unusedPool(r._1).filterNot(r._2.toSet))

    }
  }

  def reservedSize = clusterReservations.values.map(_.size.toMB).sum
  def reservedStartCount = clusterReservations.values.count {
    case p: Reservation => p.size.toMB >= 0
    case _              => false
  }
  def reservedStopCount = clusterReservations.values.count {
    case p: Reservation => p.size.toMB < 0
    case _              => false
  }
  def scheduledStartCount = clusterReservations.values.count {
    case p: Reservation => p.size.toMB >= 0
    case _              => false
  }
  def scheduledStopCount = clusterReservations.values.count {
    case p: Reservation => p.size.toMB < 0
    case _              => false
  }
  private var clusterResourcesAvailable
    : Boolean = false //track to log whenever there is a switch from cluster resources being available to not being available

  def canLaunch(memory: ByteSize, poolMemory: Long, poolConfig: ContainerPoolConfig): Boolean = {

    val localReservations = clusterReservations.values.map(_.size) //active local reservations
    val remoteRes = remoteReservations.values.toList.flatten.map(_.size) //remote/stale reservations

    val allRes = localReservations ++ remoteRes
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
  def addReservation(ref: ActorRef, size: ByteSize): Unit = {
    clusterReservations = clusterReservations + (ref -> Reservation(size))
  }
  def releaseReservation(ref: ActorRef): Unit = {
    clusterReservations = clusterReservations - ref
  }
  def allowMoreStarts(config: ContainerPoolConfig): Boolean =
    clusterReservations
      .count({ case (_, state) => state.size.toMB > 0 }) < config.clusterManagedResourceMaxStarts //only positive reservations affect ability to start

  class ContainerPoolClusterData(instanceId: InvokerInstanceId, pool: ActorRef) extends Actor {

    var lastUnused: List[RemoteContainerRef] = List.empty
    var lastReservations: List[Reservation] = List.empty
    var lastStats: Map[String, NodeStats] = Map.empty
    def updateRemoteReservations(id: Int, reservations: List[Reservation]) = {
      remoteReservations = remoteReservations + (id -> reservations)
    }

    override def postStop(): Unit = {
      //remove this invoker from ids list
      replicator ! Update(InvokerIdsKey, ORSet.empty[Int], WriteLocal)(_ - myId)
    }
    override def receive: Receive = {
      case NodeStatsUpdate(stats) =>
        logging.info(
          this,
          s"received node stats ${stats} reserved/scheduled ${clusterReservations.size} containers ${reservedSize}MB")
        clusterActionHostStats = stats
        if (!prewarmsInitialized) { //we assume that when stats are received, we should startup prewarm containers
          prewarmsInitialized = true
          logging.info(this, "initializing prewarmpool after stats recevied")
          //        initPrewarms()
          pool ! InitPrewarms
        }

        //only signal updates (to pool or replicator) in case things have changed
        if (lastStats != stats) {
          lastStats = stats
          pool ! ResourceUpdate
        }

        //update this invokers reservations seen by other invokers
        val reservations = clusterReservations.values.toList
        if (lastReservations != reservations) {
          lastReservations = reservations
          logging.info(
            this,
            s"invoker ${myId} now has ${reservations.size} reservations (${reservations.map(_.size.toMB).sum}MB)")
          replicator ! Update(myKey, LWWRegister[List[Reservation]](List.empty), WriteLocal)(reg =>
            reg.withValue(reservations))
        }
        //update this invokers idles seen by other invokers
        val idles = unused.map(f => RemoteContainerRef(f._2.memoryLimit, f._2.lastUsed)).toList
        if (lastUnused != idles) {
          lastUnused = idles
          logging.info(this, s"invoker ${myId} now has ${idles.size} idles (${idles.map(_.size.toMB).sum}MB)")
          replicator ! Update(myUnusedKey, LWWRegister[List[RemoteContainerRef]](List.empty), WriteLocal)(reg =>
            reg.withValue(idles))
        }

      case r: ReleaseFree =>
        logging.info(this, s"got releasefree from ${sender()}")
        //forward to the pool
        pool.forward(r)
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
            updateRemoteReservations(id, newValue)
          } else {
            val unusedKey = LWWRegisterKey[List[RemoteContainerRef]]("unused" + id)
            val newValue = c.get(unusedKey).value
            println(s"updating unused pool ${newValue}")
            unusedPool = unusedPool + (id -> newValue)
          }
        }
      case c @ Changed(InvokerIdsKey) =>
        val newValue = c.get(InvokerIdsKey).elements
        if (reservationKeys.keySet != newValue) {
          val deleted = reservationKeys.keySet.diff(newValue)
          val added = newValue.diff(reservationKeys.keySet)
          added.foreach { id =>
            println(s"adding id ${id}")
            val idKey = LWWRegisterKey[List[Reservation]]("reservation" + id)
            if (id != myId) { //skip my own id
              val unusedKey = LWWRegisterKey[List[RemoteContainerRef]]("unused" + id)
              reservationKeys = reservationKeys + (id -> idKey)
              unusedKeys = unusedKeys + (id -> unusedKey)
              replicator ! Subscribe(idKey, self)
              replicator ! Subscribe(unusedKey, self)
            }

          }
          deleted.foreach { id =>
            println(s"removing id ${id}")
            val idKey = LWWRegisterKey[List[Reservation]]("reservation" + id)
            val unusedKey = LWWRegisterKey[List[RemoteContainerRef]]("unused" + id)
            reservationKeys = reservationKeys - id
            unusedKeys = unusedKeys - id
            replicator ! Unsubscribe(idKey, self)
            replicator ! Unsubscribe(unusedKey, self)
            updateRemoteReservations(id, List.empty)
          }
        }
    }
  }
  def updateUnused(newUnused: Map[ActorRef, ContainerData]): Unit = {

    unused = newUnused
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
case class RemoteContainerRef(size: ByteSize, lastUsed: Instant)
case class ReleaseFree(remoteContainerRefs: List[RemoteContainerRef])

object ContainerPoolClusterData {
  def clusterPoolActorName(instanceId: Int): String = "containerPoolCluster" + instanceId.toInt
}

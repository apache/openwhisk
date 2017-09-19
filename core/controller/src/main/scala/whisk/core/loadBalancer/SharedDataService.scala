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

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.ddata.{DistributedData, PNCounterMap, PNCounterMapKey}
import akka.cluster.ddata.Replicator._
import whisk.common.AkkaLogging

case class IncreaseCounter(key: String, value: Long)
case class DecreaseCounter(key: String, value: Long)
case class ReadCounter(key: String)
case class RemoveCounter(key: String)
case object GetMap

/**
 * Companion object to specify actor properties from the outside, e.g. name of the shared map and cluster seed nodes
 */
object SharedDataService {
  def props(storageName: String): Props =
    Props(new SharedDataService(storageName))
}

class SharedDataService(storageName: String) extends Actor with ActorLogging {

  val replicator = DistributedData(context.system).replicator

  val logging = new AkkaLogging(context.system.log)

  val storage = PNCounterMapKey[String](storageName)

  implicit val node = Cluster(context.system)

  /**
   * Subscribe this node for the changes in the Map, initialize the Map
   */
  override def preStart(): Unit = {
    replicator ! Subscribe(storage, self)
    node.subscribe(self, initialStateMode = InitialStateAsEvents, classOf[MemberEvent], classOf[UnreachableMember])
    replicator ! Update(storage, PNCounterMap.empty[String], writeLocal)(_.remove(node, "0"))
  }
  override def postStop(): Unit = node.unsubscribe(self)

  /**
   * CRUD operations on the counter, process cluster member events for logging
   * @return
   */
  def receive = {

    case (IncreaseCounter(key, increment)) =>
      replicator ! Update(storage, PNCounterMap.empty[String], writeLocal)(_.increment(key, increment))

    case (DecreaseCounter(key, decrement)) =>
      replicator ! Update(storage, PNCounterMap[String], writeLocal)(_.decrement(key, decrement))

    case GetMap =>
      replicator ! Get(storage, readLocal, request = Some((sender())))

    case MemberUp(member) =>
      logging.info(this, "Member is Up: " + member.address)

    case MemberRemoved(member, previousStatus) =>
      logging.warn(this, s"Member is Removed: ${member.address} after $previousStatus")

    case c @ Changed(_) =>
      logging.debug(this, "Current elements: " + c.get(storage))

    case g @ GetSuccess(_, Some((replyTo: ActorRef))) =>
      val map = g.get(storage).entries
      replyTo ! map

    case g @ GetSuccess(_, Some((replyTo: ActorRef, key: String))) =>
      if (g.get(storage).contains(key)) {
        val response = g.get(storage).getValue(key).intValue()
        replyTo ! response
      } else
        replyTo ! None

    case _ => // ignore
  }
}

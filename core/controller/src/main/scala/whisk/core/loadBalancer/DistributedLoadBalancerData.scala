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

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.util.Timeout
import scala.collection.mutable
import scala.concurrent.duration._
import whisk.common.Logging
import whisk.core.entity.ActivationId
import whisk.core.entity.InstanceId
import whisk.core.entity.UUID

/**
 * Encapsulates data used for loadbalancer and active-ack bookkeeping.
 *
 * Note: The state keeping is backed by distributed akka actors. All CRUDs operations are done on local values, thus
 * a stale value might be read.
 */
class DistributedLoadBalancerData(instance: InstanceId, monitor: Option[ActorRef] = None)(
  implicit actorSystem: ActorSystem,
  logging: Logging)
    extends LoadBalancerData {

  implicit val timeout = Timeout(5.seconds)
  implicit val executionContext = actorSystem.dispatcher
  private val overflowKey = "overflow"
  private val activationsById = mutable.Map[ActivationId, ActivationEntry]()

  private val localData = new LocalLoadBalancerData()
  private var sharedDataInvokers = Map[String, Map[Int, Int]]()
  private var sharedDataNamespaces = Map[String, Map[Int, Int]]()
  private var sharedDataOverflow = Map[String, Map[Int, Int]]()

  private val updateMonitor = actorSystem.actorOf(Props(new Actor {
    override def receive = {
      case Updated(storageName, entries) =>
        monitor.foreach(_ ! Updated(storageName, entries))
        storageName match {
          case "Invokers"   => sharedDataInvokers = entries
          case "Namespaces" => sharedDataNamespaces = entries
          case "Overflow"   => sharedDataOverflow = entries
        }
    }
  }))

  private val sharedStateInvokers = actorSystem.actorOf(
    SharedDataService.props("Invokers", updateMonitor),
    name =
      "SharedDataServiceInvokers" + UUID())
  private val sharedStateNamespaces = actorSystem.actorOf(
    SharedDataService.props("Namespaces", updateMonitor),
    name =
      "SharedDataServiceNamespaces" + UUID())
  private val sharedStateOverflow = actorSystem.actorOf(
    SharedDataService.props("Overflow", updateMonitor),
    name =
      "SharedDataServiceOverflow" + UUID())
  def totalActivationCount = {
    val shared = sharedDataInvokers.values.flatten.filter(_._1 != instance.toInt).map(_._2).sum
    shared + localData.totalActivationCount
  }
  def activationCountOn(namespace: UUID): Int = {
    val shared = sharedDataNamespaces.getOrElse(namespace.toString, Map()).filter(_._1 != instance.toInt).values.sum
    shared + localData.activationCountOn(namespace)
  }

  def activationCountPerInvoker: Map[String, Int] = {
    val shared = sharedDataInvokers.mapValues(_.filter(_._1 != instance.toInt).values.sum)
    val local = localData.activationCountPerInvoker
    local ++ shared.map { case (k, v) => k -> (v + local.getOrElse(k, 0)) }
  }

  def activationById(activationId: ActivationId): Option[ActivationEntry] = {
    localData.activationById(activationId)
    //NOTE: activations are NOT replicated, only the counters
  }

  def putActivation(id: ActivationId, update: => ActivationEntry, isOverflow: Boolean = false): ActivationEntry = {
    activationsById.getOrElseUpdate(id, {
      val entry = update
      //update the shared stats
      //if we are processing an overflow message, do NOT double count against namespace
      if (!isOverflow) {
        sharedStateNamespaces ! IncreaseCounter(entry.namespaceId.asString, instance, 1)
      }
      //if the initial processing msg is routed to invoker, increase the invoker counter
      //otherwise increase the overflow counter
      entry.invokerName match {
        case Some(i) => sharedStateInvokers ! IncreaseCounter(i.toString, instance, 1)
        case None    => sharedStateOverflow ! IncreaseCounter(overflowKey, instance, 1)
      }
      logging.debug(this, "increased shared counters")
      //store the activation
      localData.putActivation(id, entry)
      entry
    })
  }

  def removeActivation(entry: ActivationEntry): Option[ActivationEntry] = {
    activationsById.remove(entry.id).map { activationEntry =>
      if (!activationEntry.isOverflow) {
        //update the shared stats
        sharedStateNamespaces ! DecreaseCounter(entry.namespaceId.asString, instance, 1)
      }
      entry.invokerName match {
        case Some(i) => sharedStateInvokers ! DecreaseCounter(i.toString, instance, 1)
        case None    => sharedStateOverflow ! DecreaseCounter(overflowKey, instance, 1)
      }
      logging.debug(this, s"decreased shared counters ")
      //remove the activation
      localData.removeActivation(entry)
      activationEntry
    }
  }

  def removeActivation(aid: ActivationId): Option[ActivationEntry] = {
    activationsById.get(aid).flatMap(removeActivation)
  }

  /**
   * Get the number of activations waiting at the overflow queue
   * @return
   */
  override def overflowActivationCount = {
    val shared = sharedDataOverflow.values.flatten.filter(_._1 != instance.toInt).map(_._2).sum
    shared + localData.overflowActivationCount
  }
}

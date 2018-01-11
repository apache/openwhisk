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
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import whisk.common.Logging
import whisk.core.entity.ActivationId
import whisk.core.entity.UUID

/**
 * Encapsulates data used for loadbalancer and active-ack bookkeeping.
 *
 * Note: The state keeping is backed by distributed akka actors. All CRUDs operations are done on local values, thus
 * a stale value might be read.
 */
class DistributedLoadBalancerData(monitor: Option[ActorRef] = None)(implicit actorSystem: ActorSystem, logging: Logging)
    extends LocalLoadBalancerData {

  implicit val timeout = Timeout(5.seconds)
  implicit val executionContext = actorSystem.dispatcher

  private val updateMonitor = actorSystem.actorOf(Props(new Actor {
    override def receive = {
      case Updated(storageName, entries) =>
        monitor.foreach(_ ! Updated(storageName, entries))
        storageName match {
          case "Invokers" =>
            //reset the state with updates:
            val builder = TrieMap.newBuilder[String, AtomicInteger]
            entries.map(e => {
              builder += ((e._1, new AtomicInteger(e._2)))
            })
            activationByInvoker = builder.result()
          case "Namespaces" => //sharedDataNamespaces = entries
            //reset the state with updates:
            val builder = TrieMap.newBuilder[UUID, AtomicInteger]
            entries.map(e => {
              builder += ((UUID(e._1), new AtomicInteger(e._2)))
            })
            activationByNamespaceId = builder.result()
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

  override def putActivation(id: ActivationId, update: => ActivationEntry): ActivationEntry = {
    activationsById.getOrElseUpdate(id, {
      val entry = super.putActivation(id, update)
      sharedStateNamespaces ! IncreaseCounter(entry.namespaceId.asString, 1)
      sharedStateInvokers ! IncreaseCounter(entry.invokerName.toString, 1)
      logging.debug(this, "increased shared counters")
      entry
    })
  }

  override def removeActivation(entry: ActivationEntry): Option[ActivationEntry] = {
    super.removeActivation(entry).map { activationEntry =>
      sharedStateNamespaces ! DecreaseCounter(entry.namespaceId.asString, 1)
      sharedStateInvokers ! DecreaseCounter(entry.invokerName.toString, 1)
      logging.debug(this, s"decreased shared counters ")
      activationEntry
    }
  }

}

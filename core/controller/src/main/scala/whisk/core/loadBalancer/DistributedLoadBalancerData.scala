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

import akka.actor.ActorSystem
import akka.util.Timeout
import akka.pattern.ask
import whisk.common.Logging
import whisk.core.entity.{ActivationId, UUID}

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.concurrent.duration._

/**
 * Encapsulates data used for loadbalancer and active-ack bookkeeping.
 *
 * Note: The state keeping is backed by distributed akka actors. All CRUDs operations are done on local values, thus
 * a stale value might be read.
 */
class DistributedLoadBalancerData(implicit actorSystem: ActorSystem, logging: Logging) extends LoadBalancerData {

  implicit val timeout = Timeout(5.seconds)
  implicit val executionContext = actorSystem.dispatcher
  private val activationsById = TrieMap[ActivationId, ActivationEntry]()

  private val sharedStateInvokers = actorSystem.actorOf(
    SharedDataService.props("Invokers"),
    name =
      "SharedDataServiceInvokers" + UUID())
  private val sharedStateNamespaces = actorSystem.actorOf(
    SharedDataService.props("Namespaces"),
    name =
      "SharedDataServiceNamespaces" + UUID())

  def totalActivationCount =
    (sharedStateInvokers ? GetMap).mapTo[Map[String, BigInt]].map(_.values.sum.toInt)

  def activationCountOn(namespace: UUID): Future[Int] = {
    (sharedStateNamespaces ? GetMap)
      .mapTo[Map[String, BigInt]]
      .map(_.mapValues(_.toInt).getOrElse(namespace.toString, 0))
  }

  def activationCountPerInvoker: Future[Map[String, Int]] = {
    (sharedStateInvokers ? GetMap).mapTo[Map[String, BigInt]].map(_.mapValues(_.toInt))
  }

  def activationById(activationId: ActivationId): Option[ActivationEntry] = {
    activationsById.get(activationId)
  }

  def putActivation(id: ActivationId, update: => ActivationEntry): ActivationEntry = {
    activationsById.getOrElseUpdate(id, {
      val entry = update
      sharedStateNamespaces ! IncreaseCounter(entry.namespaceId.asString, 1)
      sharedStateInvokers ! IncreaseCounter(entry.invokerName.toString, 1)
      logging.debug(this, "increased shared counters")
      entry
    })
  }

  def removeActivation(entry: ActivationEntry): Option[ActivationEntry] = {
    activationsById.remove(entry.id).map { activationEntry =>
      sharedStateInvokers ! DecreaseCounter(entry.invokerName.toString, 1)
      sharedStateNamespaces ! DecreaseCounter(entry.namespaceId.asString, 1)
      logging.debug(this, "decreased shared counters")
      activationEntry
    }
  }

  def removeActivation(aid: ActivationId): Option[ActivationEntry] = {
    activationsById.get(aid).flatMap(removeActivation)
  }
}

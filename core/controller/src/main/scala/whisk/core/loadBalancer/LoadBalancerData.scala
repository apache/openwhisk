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
import akka.event.Logging
import whisk.core.entity.{ActivationId, UUID}
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

trait BookkeepingData {

  /** Get the number of activations across all namespaces. */
  def totalActivationCount: Future[Int]

  /**
   * Get the number of activations for a specific namespace.
   *
   * @param namespace The namespace to get the activation count for
   * @return a map (namespace -> number of activations in the system)
   */
  def activationCountOn(namespace: UUID): Future[Int]

  /**
   * Get the number of activations for each invoker.
   *
   * @return a map (invoker -> number of activations queued for the invoker)
   */
  def activationCountPerInvoker: Future[Map[String, Int]]

  /**
   * Get an activation entry for a given activation id.
   *
   * @param activationId activation id to get data for
   * @return the respective activation or None if it doesn't exist
   */
  def activationById(activationId: ActivationId): Option[ActivationEntry]

  /**
   * Adds an activation entry.
   *
   * @param id     identifier to deduplicate the entry
   * @param update block calculating the entry to add.
   *               Note: This is evaluated iff the entry
   *               didn't exist before.
   * @return the entry calculated by the block or iff it did
   *         exist before the entry from the state
   */
  def putActivation(id: ActivationId, update: => ActivationEntry): ActivationEntry

  /**
   * Removes the given entry.
   *
   * @param entry the entry to remove
   * @return The deleted entry or None if nothing got deleted
   */
  def removeActivation(entry: ActivationEntry): Option[ActivationEntry]

  /**
   * Removes the activation identified by the given activation id.
   *
   * @param aid activation id to remove
   * @return The deleted entry or None if nothing got deleted
   */
  def removeActivation(aid: ActivationId): Option[ActivationEntry]
}

/**
 * Encapsulates data used for loadbalancer and active-ack bookkeeping.
 *
 * Note: The state keeping is backed by distributed akka actors. All CRUDs operations are done on local values, thus
 * a stale value might be read.
 */
class LoadBalancerData(implicit val actorSystem: ActorSystem) extends BookkeepingData {

  implicit val timeout = Timeout(5.seconds)
  private val activationsById = TrieMap[ActivationId, ActivationEntry]()
  private val sharedStateInvokers = actorSystem.actorOf(
    SharedDataService.props("Invokers"),
    name =
      "SharedDataServiceInvokers" + UUID())
  private val sharedStateNamespaces = actorSystem.actorOf(
    SharedDataService.props("Namespaces"),
    name =
      "SharedDataServiceNamespaces" + UUID())

  val logging = Logging(actorSystem, sharedStateInvokers)

  def totalActivationCount =
    (sharedStateNamespaces ? GetTheMap()).mapTo[MapWithCounters].map(_.dataMap.values.sum.toInt)

  def activationCountOn(namespace: UUID): Future[Int] = {
    (sharedStateNamespaces ? GetTheMap())
      .mapTo[MapWithCounters]
      .map(_.dataMap.mapValues(_.toInt).getOrElse(namespace.toString, 0))
  }

  def activationCountPerInvoker: Future[Map[String, Int]] = {
    (sharedStateInvokers ? GetTheMap()).mapTo[MapWithCounters].map(_.dataMap.mapValues(_.toInt))
  }

  def activationById(activationId: ActivationId): Option[ActivationEntry] = {
    activationsById.get(activationId)
  }

  def putActivation(id: ActivationId, update: => ActivationEntry): ActivationEntry = {
    activationsById.getOrElseUpdate(id, {
      val entry = update
      sharedStateNamespaces ! IncreaseCounter(entry.namespaceId.asString, 1)
      sharedStateInvokers ! IncreaseCounter(entry.invokerName.toString, 1)
      logging.info(s"increased shared counters")
      entry
    })
  }

  def removeActivation(entry: ActivationEntry): Option[ActivationEntry] = {
    activationsById.remove(entry.id).map { x =>
      sharedStateInvokers ! DecreaseCounter(entry.invokerName.toString, 1)
      sharedStateNamespaces ! DecreaseCounter(entry.namespaceId.asString, 1)
      logging.info(s"decreased shared counters")
      x
    }
  }

  def removeActivation(aid: ActivationId): Option[ActivationEntry] = {
    activationsById.get(aid).flatMap(removeActivation)
  }
}

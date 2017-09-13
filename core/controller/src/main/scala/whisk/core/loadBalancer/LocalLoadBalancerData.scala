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

import java.util.concurrent.atomic.AtomicInteger

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import whisk.core.entity.{ActivationId, UUID}

/**
 * Loadbalancer bookkeeping data which are stored locally,
 * e.g. not shared with other controller instances.
 *
 * Note: The state keeping is backed by concurrent data-structures. As such,
 * concurrent reads can return stale values (especially the counters returned).
 */
class LocalLoadBalancerData() extends LoadBalancerData {

  private val activationByInvoker = TrieMap[String, AtomicInteger]()
  private val activationByNamespaceId = TrieMap[UUID, AtomicInteger]()
  private val activationsById = TrieMap[ActivationId, ActivationEntry]()
  private val totalActivations = new AtomicInteger(0)

  override def totalActivationCount: Future[Int] = Future.successful(totalActivations.get)

  override def activationCountOn(namespace: UUID): Future[Int] = {
    Future.successful(activationByNamespaceId.get(namespace).map(_.get).getOrElse(0))
  }

  override def activationCountPerInvoker: Future[Map[String, Int]] = {
    Future.successful(activationByInvoker.toMap.mapValues(_.get))
  }

  override def activationById(activationId: ActivationId): Option[ActivationEntry] = {
    activationsById.get(activationId)
  }

  override def putActivation(id: ActivationId, update: => ActivationEntry): ActivationEntry = {
    activationsById.getOrElseUpdate(id, {
      val entry = update
      totalActivations.incrementAndGet()
      activationByNamespaceId.getOrElseUpdate(entry.namespaceId, new AtomicInteger(0)).incrementAndGet()
      activationByInvoker.getOrElseUpdate(entry.invokerName.toString, new AtomicInteger(0)).incrementAndGet()
      entry
    })
  }

  override def removeActivation(entry: ActivationEntry): Option[ActivationEntry] = {
    activationsById.remove(entry.id).map { x =>
      totalActivations.decrementAndGet()
      activationByNamespaceId.getOrElseUpdate(entry.namespaceId, new AtomicInteger(0)).decrementAndGet()
      activationByInvoker.getOrElseUpdate(entry.invokerName.toString, new AtomicInteger(0)).decrementAndGet()
      x
    }
  }

  override def removeActivation(aid: ActivationId): Option[ActivationEntry] = {
    activationsById.get(aid).flatMap(removeActivation)
  }
}

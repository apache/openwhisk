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

import whisk.core.entity.{ ActivationId, UUID, WhiskActivation }
import scala.collection.concurrent.TrieMap
import scala.concurrent.Promise
import whisk.core.entity.InstanceId

/** Encapsulates data relevant for a single activation */
case class ActivationEntry(id: ActivationId, namespaceId: UUID, invokerName: InstanceId, promise: Promise[Either[ActivationId, WhiskActivation]])

/**
 * Encapsulates data used for loadbalancer and active-ack bookkeeping.
 *
 * Note: The state keeping is backed by concurrent data-structures. As such,
 * concurrent reads can return stale values (especially the counters returned).
 */
class LoadBalancerData() {

    type TrieSet[T] = TrieMap[T, Unit]

    private val activationByInvoker = new TrieMap[InstanceId, TrieSet[ActivationEntry]]
    private val activationByNamespaceId = new TrieMap[UUID, TrieSet[ActivationEntry]]
    private val activationsById = new TrieMap[ActivationId, ActivationEntry]

    /**
     * Get the number of activations for each namespace.
     *
     * @return a map (namespace -> number of activations in the system)
     */
    def activationCountByNamespace: Map[UUID, Int] = {
        activationByNamespaceId.toMap.mapValues(_.size)
    }

    /**
     * Get the number of activations for each invoker.
     *
     * @return a map (invoker -> number of activations queued for the invoker)
     */
    def activationCountByInvoker: Map[InstanceId, Int] = {
        activationByInvoker.toMap.mapValues(_.size)
    }

    /**
     * Get an activation entry for a given activation id.
     *
     * @param activationId activation id to get data for
     * @return the respective activation or None if it doesn't exist
     */
    def activationById(activationId: ActivationId): Option[ActivationEntry] = {
        activationsById.get(activationId)
    }

    /**
     * Adds an activation entry.
     *
     * @param id identifier to deduplicate the entry
     * @param update block calculating the entry to add.
     *               Note: This is evaluated iff the entry
     *               didn't exist before.
     * @return the entry calculated by the block or iff it did
     *         exist before the entry from the state
     */
    def putActivation(id: ActivationId, update: => ActivationEntry): ActivationEntry = {
        activationsById.getOrElseUpdate(id, {
            val entry = update
            activationByNamespaceId.getOrElseUpdate(entry.namespaceId, new TrieSet[ActivationEntry]).put(entry, {})
            activationByInvoker.getOrElseUpdate(entry.invokerName, new TrieSet[ActivationEntry]).put(entry, {})
            entry
        })
    }

    /**
     * Removes the given entry.
     *
     * @param entry the entry to remove
     * @return The deleted entry or None if nothing got deleted
     */
    def removeActivation(entry: ActivationEntry): Option[ActivationEntry] = {
        activationsById.remove(entry.id).map { x =>
            activationByNamespaceId.getOrElseUpdate(x.namespaceId, new TrieSet[ActivationEntry]).remove(entry)
            activationByInvoker.getOrElseUpdate(x.invokerName, new TrieSet[ActivationEntry]).remove(entry)
            x
        }
    }

    /**
     * Removes the activation identified by the given activation id.
     *
     * @param aid activation id to remove
     * @return The deleted entry or None if nothing got deleted
     */
    def removeActivation(aid: ActivationId): Option[ActivationEntry] = {
        activationsById.get(aid).flatMap(removeActivation)
    }
}

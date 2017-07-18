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

import java.time.Instant
import whisk.core.entity.{ActivationId, UUID, WhiskActivation}
import scala.collection.concurrent.TrieMap
import scala.concurrent.Promise

case class ActivationEntry(id: ActivationId, namespaceId: UUID, invokerName: String, created: Instant, promise: Promise[Either[ActivationId, WhiskActivation]])

class LoadBalancerData() {

    type TrieSet[T] = TrieMap[T, Unit]

    private val activationByInvoker = new TrieMap[String, TrieSet[ActivationEntry]]
    private val activationByNamespaceId = new TrieMap[UUID, TrieSet[ActivationEntry]]
    private val activationsById = new TrieMap[ActivationId, ActivationEntry]

    def activationCountByNamespace = {
        activationByNamespaceId.toMap mapValues { _.size }
    }

    def activationCountByInvoker = {
        activationByInvoker.toMap mapValues { _.size }
    }

    def activationsByInvoker(invokerName: String) = {
        activationByInvoker.getOrElseUpdate(invokerName, new TrieSet[ActivationEntry])
    }

    def activationById(activationId: ActivationId) = {
        activationsById.get(activationId)
    }

    def activationsByNamespaceId(namespaceId: UUID) = {
        activationByNamespaceId.getOrElseUpdate(namespaceId, new TrieSet[ActivationEntry])
    }

    def putActivation(entry: ActivationEntry): Any = {
        activationsById.put(entry.id, entry)
        activationByNamespaceId.getOrElseUpdate(entry.namespaceId, new TrieSet[ActivationEntry]).put(entry, {})
        activationByInvoker.getOrElseUpdate(entry.invokerName, new TrieSet[ActivationEntry]).put(entry, {})
    }

    def removeActivation(entry: ActivationEntry): ActivationEntry = {
        activationsById.remove(entry.id)
        activationByNamespaceId.getOrElseUpdate(entry.namespaceId, new TrieSet[ActivationEntry]).remove(entry)
        activationByInvoker.getOrElseUpdate(entry.invokerName, new TrieSet[ActivationEntry]).remove(entry)
        entry
    }

    def removeActivation(aid: ActivationId): Option[ActivationEntry] = {
        activationsById.get(aid).map(removeActivation)
    }
}

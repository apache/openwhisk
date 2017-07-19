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

    def activationCountByNamespace: Map[UUID, Int] = {
        activationByNamespaceId.toMap.mapValues(_.size)
    }

    def activationCountByInvoker: Map[String, Int] = {
        activationByInvoker.toMap.mapValues(_.size)
    }

    def activationsByInvoker(invokerName: String): TrieSet[ActivationEntry] = {
        activationByInvoker.getOrElseUpdate(invokerName, new TrieSet[ActivationEntry])
    }

    def activationById(activationId: ActivationId): Option[ActivationEntry] = {
        activationsById.get(activationId)
    }

    def putActivation(entry: ActivationEntry): Unit = {
        activationsById.put(entry.id, entry)
        activationByNamespaceId.getOrElseUpdate(entry.namespaceId, new TrieSet[ActivationEntry]).put(entry, {})
        activationByInvoker.getOrElseUpdate(entry.invokerName, new TrieSet[ActivationEntry]).put(entry, {})
    }

    def removeActivation(entry: ActivationEntry): Option[ActivationEntry] = {
        activationsById.remove(entry.id).map { x =>
            activationByNamespaceId.getOrElseUpdate(x.namespaceId, new TrieSet[ActivationEntry]).remove(entry)
            activationByInvoker.getOrElseUpdate(x.invokerName, new TrieSet[ActivationEntry]).remove(entry)
            x
        }
    }

    def removeActivation(aid: ActivationId): Option[ActivationEntry] = {
        activationsById.get(aid).flatMap(removeActivation)
    }
}

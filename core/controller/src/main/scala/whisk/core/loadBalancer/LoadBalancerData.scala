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


class LoadBalancerDataWithLocalMap {

 type TrieSet[T] = TrieMap[T, Unit]

 private val activationByInvoker = new TrieMap[String, TrieSet[ActivationEntry]]
 private val activationByNamespaceId = new TrieMap[UUID, TrieSet[ActivationEntry]]


 def getActivationCountByNamespace() = {
  activationByNamespaceId.toMap mapValues { _.size }
 }

 def getActivationCountByInvoker() = {
  activationByInvoker.toMap mapValues { _.size }
 }

 def activationsByInvokerSize() = {
  activationByInvoker.mapValues(_.size)
 }


 def removeActivationByNamespaceId(namespaceId :UUID, entry :ActivationEntry) = {
  activationByNamespaceId.getOrElseUpdate(namespaceId, new TrieSet[ActivationEntry]).remove(entry)
 }

 def removeActivationByNamespaceIdWithResponse(namespaceId :UUID, entry :ActivationEntry) = {
  activationByNamespaceId.get(namespaceId) map { _.remove(entry) }
 }


 def putActivationByNamespaceId(namespaceId :UUID, entry :ActivationEntry) = {
  activationByNamespaceId.getOrElseUpdate(namespaceId, new TrieSet[ActivationEntry]).put(entry, {})
 }

 def putActivationbyInvoker(invokerName :String, entry :ActivationEntry) = {
  activationByInvoker.getOrElseUpdate(invokerName, new TrieSet[ActivationEntry]).put(entry, {})
 }

 def removeActivationByInvoker(invokerIndex :String, entry :ActivationEntry) = {
  activationByInvoker.getOrElseUpdate(invokerIndex, new TrieSet[ActivationEntry]).remove(entry)
 }

 def getActivationsByInvoker(invokerName :String) ={
  activationByInvoker.getOrElseUpdate(invokerName, new TrieSet[ActivationEntry])
 }

}
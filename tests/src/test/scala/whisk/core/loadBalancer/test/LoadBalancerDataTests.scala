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

package whisk.core.loadBalancer.test

import org.scalatest.{FlatSpec, Matchers}
import whisk.core.entity.{ActivationId, UUID, WhiskActivation}
import whisk.core.loadBalancer.{ActivationEntry, LoadBalancerData}

import scala.concurrent.{Promise}
import whisk.core.entity.InstanceId

class LoadBalancerDataTests extends FlatSpec with Matchers {

  val activationIdPromise = Promise[Either[ActivationId, WhiskActivation]]()
  val firstEntry: ActivationEntry = ActivationEntry(ActivationId(), UUID(), InstanceId(0), activationIdPromise)
  val secondEntry: ActivationEntry = ActivationEntry(ActivationId(), UUID(), InstanceId(1), activationIdPromise)

  behavior of "LoadBalancerData"

  it should "return the number of activations for a namespace" in {

    val loadBalancerData = new LoadBalancerData()
    loadBalancerData.putActivation(firstEntry.id, firstEntry)

    loadBalancerData.activationCountOn(firstEntry.namespaceId) shouldBe 1
    loadBalancerData.activationCountOn(firstEntry.invokerName) shouldBe 1
    loadBalancerData.activationById(firstEntry.id) shouldBe Some(firstEntry)
  }

  it should "return the number of activations for each invoker" in {

    val loadBalancerData = new LoadBalancerData()
    loadBalancerData.putActivation(firstEntry.id, firstEntry)
    loadBalancerData.putActivation(secondEntry.id, secondEntry)

    loadBalancerData.activationCountOn(firstEntry.invokerName) shouldBe 1
    loadBalancerData.activationCountOn(secondEntry.invokerName) shouldBe 1
    loadBalancerData.activationById(firstEntry.id) shouldBe Some(firstEntry)
    loadBalancerData.activationById(secondEntry.id) shouldBe Some(secondEntry)
  }

  it should "remove activations and reflect that accordingly" in {

    val loadBalancerData = new LoadBalancerData()
    loadBalancerData.putActivation(firstEntry.id, firstEntry)
    loadBalancerData.activationCountOn(firstEntry.invokerName) shouldBe 1
    loadBalancerData.activationCountOn(firstEntry.namespaceId) shouldBe 1

    loadBalancerData.removeActivation(firstEntry)

    loadBalancerData.activationCountOn(firstEntry.invokerName) shouldBe 0
    loadBalancerData.activationCountOn(firstEntry.namespaceId) shouldBe 0
    loadBalancerData.activationById(firstEntry.id) shouldBe None
  }

  it should "remove activations from all 3 maps by activation id" in {

    val loadBalancerData = new LoadBalancerData()
    loadBalancerData.putActivation(firstEntry.id, firstEntry)
    loadBalancerData.activationCountOn(firstEntry.invokerName) shouldBe 1

    loadBalancerData.removeActivation(firstEntry.id)

    loadBalancerData.activationCountOn(firstEntry.invokerName) shouldBe 0
  }

  it should "return None if the entry doesn't exist when we remove it" in {
    val loadBalancerData = new LoadBalancerData()
    loadBalancerData.removeActivation(firstEntry) shouldBe None
  }

  it should "respond with different values accordingly" in {

    val entry = ActivationEntry(ActivationId(), UUID(), InstanceId(1), activationIdPromise)
    val entrySameInvokerAndNamespace = entry.copy(id = ActivationId())
    val entrySameInvoker = entry.copy(id = ActivationId(), namespaceId = UUID())

    val loadBalancerData = new LoadBalancerData()
    loadBalancerData.putActivation(entry.id, entry)
    loadBalancerData.activationCountOn(entry.namespaceId) shouldBe 1
    loadBalancerData.activationCountOn(entry.invokerName) shouldBe 1

    loadBalancerData.putActivation(entrySameInvokerAndNamespace.id, entrySameInvokerAndNamespace)
    loadBalancerData.activationCountOn(entry.namespaceId) shouldBe 2
    loadBalancerData.activationCountOn(entry.invokerName) shouldBe 2

    loadBalancerData.putActivation(entrySameInvoker.id, entrySameInvoker)
    loadBalancerData.activationCountOn(entry.namespaceId) shouldBe 2
    loadBalancerData.activationCountOn(entry.invokerName) shouldBe 3

    loadBalancerData.removeActivation(entrySameInvokerAndNamespace)
    loadBalancerData.activationCountOn(entry.namespaceId) shouldBe 1
    loadBalancerData.activationCountOn(entry.invokerName) shouldBe 2

    // removing non existing entry doesn't mess up
    loadBalancerData.removeActivation(entrySameInvokerAndNamespace)
    loadBalancerData.activationCountOn(entry.namespaceId) shouldBe 1
    loadBalancerData.activationCountOn(entry.invokerName) shouldBe 2

  }

  it should "not add the same entry more then once" in {

    val loadBalancerData = new LoadBalancerData()

    loadBalancerData.putActivation(firstEntry.id, firstEntry)
    loadBalancerData.activationCountOn(firstEntry.invokerName) shouldBe 1
    loadBalancerData.activationCountOn(firstEntry.namespaceId) shouldBe 1

    loadBalancerData.putActivation(firstEntry.id, firstEntry)
    loadBalancerData.activationCountOn(firstEntry.invokerName) shouldBe 1
    loadBalancerData.activationCountOn(firstEntry.namespaceId) shouldBe 1
  }

  it should "not evaluate the given block if an entry already exists" in {

    val loadBalancerData = new LoadBalancerData()
    var called = 0

    val entry = loadBalancerData.putActivation(firstEntry.id, {
      called += 1
      firstEntry
    })

    called shouldBe 1

    // entry already exists, should not evaluate the block
    val entryAfterSecond = loadBalancerData.putActivation(firstEntry.id, {
      called += 1
      firstEntry
    })

    called shouldBe 1
    entry shouldBe entryAfterSecond
  }

  it should "not evaluate the given block even if an entry is different (but has the same id)" in {

    val loadBalancerData = new LoadBalancerData()
    var called = 0
    val entrySameId = secondEntry.copy(id = firstEntry.id)

    val entry = loadBalancerData.putActivation(firstEntry.id, {
      called += 1
      firstEntry
    })

    called shouldBe 1
    loadBalancerData.activationCountOn(firstEntry.invokerName) shouldBe 1
    loadBalancerData.activationCountOn(firstEntry.namespaceId) shouldBe 1

    // entry already exists, should not evaluate the block and change the state
    val entryAfterSecond = loadBalancerData.putActivation(entrySameId.id, {
      called += 1
      entrySameId
    })

    called shouldBe 1
    entry shouldBe entryAfterSecond
    loadBalancerData.activationCountOn(firstEntry.invokerName) shouldBe 1
    loadBalancerData.activationCountOn(firstEntry.namespaceId) shouldBe 1
  }

}

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

import java.time.Instant

import org.scalatest.{FlatSpec, Matchers}
import whisk.core.entity.{ActivationId, UUID, WhiskActivation}
import whisk.core.loadBalancer.{ActivationEntry, LoadBalancerData}

import scala.concurrent.{Promise}

class LoadBalancerDataTests extends FlatSpec with Matchers {

    val activationIdPromise = Promise[Either[ActivationId, WhiskActivation]]()
    val firstEntry: ActivationEntry = ActivationEntry(ActivationId(), UUID(), "invoker0",
        Instant.now, activationIdPromise)
    val secondEntry: ActivationEntry = ActivationEntry(ActivationId(), UUID(), "invoker1", Instant
      .now, activationIdPromise)

    behavior of "LoadBalancerData"

    it should "return the number of activations for a namespace" in {

        val loadBalancerData = new LoadBalancerData()
        loadBalancerData.putActivation(firstEntry)

        val result = loadBalancerData.activationCountByNamespace
        result shouldBe Map(firstEntry.namespaceId -> 1)
        loadBalancerData.activationCountByInvoker("invoker0") shouldBe 1
        loadBalancerData.activationById(firstEntry.id) shouldBe Some(firstEntry)
    }

    it should "return the number of activations for each invoker" in {

        val loadBalancerData = new LoadBalancerData()
        loadBalancerData.putActivation(firstEntry)
        loadBalancerData.putActivation(secondEntry)

        val result = loadBalancerData.activationCountByInvoker

        result shouldBe Map(firstEntry.invokerName -> 1, secondEntry.invokerName -> 1)

        loadBalancerData.activationCountByInvoker("invoker0") shouldBe 1
        loadBalancerData.activationCountByInvoker("invoker1") shouldBe 1
        loadBalancerData.activationById(firstEntry.id) shouldBe Some(firstEntry)
        loadBalancerData.activationById(secondEntry.id) shouldBe Some(secondEntry)
    }

    it should "remove activations from all 3 maps" in {

        val loadBalancerData = new LoadBalancerData()
        loadBalancerData.putActivation(firstEntry)
        loadBalancerData.putActivation(secondEntry)

        loadBalancerData.removeActivation(firstEntry)
        loadBalancerData.removeActivation(secondEntry)

        val activationsByInvoker = loadBalancerData.activationCountByInvoker
        val activationsByNamespace = loadBalancerData.activationCountByNamespace

        activationsByInvoker.values.sum shouldBe 0
        activationsByNamespace.values.sum shouldBe 0
        loadBalancerData.activationById(firstEntry.id) shouldBe None
        loadBalancerData.activationById(secondEntry.id) shouldBe None
    }

    it should "remove activations from all 3 maps by activation id" in {

        val loadBalancerData = new LoadBalancerData()
        loadBalancerData.putActivation(firstEntry)

        loadBalancerData.removeActivation(firstEntry.id)

        val activationsByInvoker = loadBalancerData.activationCountByInvoker
        val activationsByNamespace = loadBalancerData.activationCountByNamespace

        activationsByInvoker.values.sum shouldBe 0
        activationsByNamespace.values.sum shouldBe 0
        loadBalancerData.activationById(firstEntry.id) shouldBe None

    }

    it should "return None if the entry doesn't exist when we remove it" in {
        val loadBalancerData = new LoadBalancerData()
        loadBalancerData.removeActivation(firstEntry) shouldBe None
    }

    it should "respond with different values accordingly" in {

        val entry = ActivationEntry(ActivationId(), UUID(), "invoker1", Instant
          .now, activationIdPromise)
        val entrySameInvokerAndNamespace = entry.copy(id = ActivationId())
        val entrySameInvoker = entry.copy(id = ActivationId(), namespaceId = UUID())

        val loadBalancerData = new LoadBalancerData()
        loadBalancerData.putActivation(entry)
        loadBalancerData.activationCountByNamespace(entry.namespaceId) shouldBe 1
        loadBalancerData.activationCountByInvoker(entry.invokerName) shouldBe 1

        loadBalancerData.putActivation(entrySameInvokerAndNamespace)
        loadBalancerData.activationCountByNamespace(entry.namespaceId) shouldBe 2
        loadBalancerData.activationCountByInvoker(entry.invokerName) shouldBe 2

        loadBalancerData.putActivation(entrySameInvoker)
        loadBalancerData.activationCountByNamespace(entry.namespaceId) shouldBe 2
        loadBalancerData.activationCountByInvoker(entry.invokerName) shouldBe 3

        loadBalancerData.removeActivation(entrySameInvokerAndNamespace)
        loadBalancerData.activationCountByNamespace(entry.namespaceId) shouldBe 1
        loadBalancerData.activationCountByInvoker(entry.invokerName) shouldBe 2

        // removing non existing entry doesn't mess up
        loadBalancerData.removeActivation(entrySameInvokerAndNamespace)
        loadBalancerData.activationCountByNamespace(entry.namespaceId) shouldBe 1
        loadBalancerData.activationCountByInvoker(entry.invokerName) shouldBe 2

    }

}

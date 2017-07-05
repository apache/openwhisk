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

import akka.actor.{ActorSystem}
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import org.scalatest.{FlatSpec, Matchers}
import whisk.core.entity.{ActivationId, UUID, WhiskActivation}
import whisk.core.loadBalancer.{ActivationEntry, LoadBalancerData}

import scala.concurrent.{Await, Promise}
import whisk.core.entity.InstanceId

import scala.concurrent.duration._

class LoadBalancerDataTests extends FlatSpec with Matchers {

  val activationIdPromise = Promise[Either[ActivationId, WhiskActivation]]()
  val firstEntry: ActivationEntry = ActivationEntry(ActivationId(), UUID(), InstanceId(0), activationIdPromise)
  val secondEntry: ActivationEntry = ActivationEntry(ActivationId(), UUID(), InstanceId(1), activationIdPromise)

  val port = 2552
  val config = ConfigFactory
    .parseString("akka.cluster { seed-nodes = [\"akka.tcp://controller-actor-system@127.0.0" +
      s".1:$port" + "\"] }")
    .withValue("akka.remote.netty.tcp.hostname", ConfigValueFactory.fromAnyRef("127.0.0.1"))
    .withValue("akka.remote.netty.tcp.port", ConfigValueFactory.fromAnyRef(port))
    .withValue("akka.cluster.auto-down-unreachable-after", ConfigValueFactory.fromAnyRef("10s"))
    .withValue("akka.actor.provider", ConfigValueFactory.fromAnyRef("cluster"))
    .withValue("akka.remote.log-remote-lifecycle-events", ConfigValueFactory.fromAnyRef("off"))
    .withFallback(ConfigFactory.load())

  implicit val actorSystem = ActorSystem("controller-actor-system", config)

  behavior of "LoadBalancerData"

  it should "return the number of activations for a namespace" in {
    val loadBalancerData = new LoadBalancerData()
    loadBalancerData.putActivation(firstEntry.id, firstEntry)

    Await.result(loadBalancerData.activationCountOn(firstEntry.namespaceId), 1.second) shouldBe 1
    Await.result(loadBalancerData.activationCountPerInvoker, 1.second) shouldBe Map(
      firstEntry.invokerName.toString -> 1)
    loadBalancerData.activationById(firstEntry.id) shouldBe Some(firstEntry)

    // clean up after yourself
    loadBalancerData.removeActivation(firstEntry.id)
  }

  it should "return the number of activations for each invoker" in {

    val loadBalancerData = new LoadBalancerData()
    loadBalancerData.putActivation(firstEntry.id, firstEntry)
    loadBalancerData.putActivation(secondEntry.id, secondEntry)

    val res = Await.result(loadBalancerData.activationCountPerInvoker, 1.second)

    res.get(firstEntry.invokerName.toString()) shouldBe Some(1)
    res.get(secondEntry.invokerName.toString()) shouldBe Some(1)

    loadBalancerData.activationById(firstEntry.id) shouldBe Some(firstEntry)
    loadBalancerData.activationById(secondEntry.id) shouldBe Some(secondEntry)

    // clean up after yourself
    loadBalancerData.removeActivation(firstEntry.id)
    loadBalancerData.removeActivation(secondEntry.id)
  }

  it should "remove activations and reflect that accordingly" in {

    val loadBalancerData = new LoadBalancerData()
    loadBalancerData.putActivation(firstEntry.id, firstEntry)
    val res = Await.result(loadBalancerData.activationCountPerInvoker, 1.second)
    res.get(firstEntry.invokerName.toString()) shouldBe Some(1)

    Await.result(loadBalancerData.activationCountOn(firstEntry.namespaceId), 1.second) shouldBe 1

    loadBalancerData.removeActivation(firstEntry)

    val resAfterRemoval = Await.result(loadBalancerData.activationCountPerInvoker, 1.second)
    resAfterRemoval.get(firstEntry.invokerName.toString()) shouldBe Some(0)

    Await.result(loadBalancerData.activationCountOn(firstEntry.namespaceId), 1.second) shouldBe 0
    loadBalancerData.activationById(firstEntry.id) shouldBe None

  }

  it should "remove activations from all 3 maps by activation id" in {

    val loadBalancerData = new LoadBalancerData()
    loadBalancerData.putActivation(firstEntry.id, firstEntry)

    val res = Await.result(loadBalancerData.activationCountPerInvoker, 1.second)
    res.get(firstEntry.invokerName.toString()) shouldBe Some(1)

    loadBalancerData.removeActivation(firstEntry.id)

    val resAfterRemoval = Await.result(loadBalancerData.activationCountPerInvoker, 1.second)
    resAfterRemoval.get(firstEntry.invokerName.toString()) shouldBe Some(0)

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

    Await.result(loadBalancerData.activationCountOn(entry.namespaceId), 1.second) shouldBe 1
    var res = Await.result(loadBalancerData.activationCountPerInvoker, 1.second)
    res.get(entry.invokerName.toString()) shouldBe Some(1)

    loadBalancerData.putActivation(entrySameInvokerAndNamespace.id, entrySameInvokerAndNamespace)
    Await.result(loadBalancerData.activationCountOn(entry.namespaceId), 1.second) shouldBe 2
    res = Await.result(loadBalancerData.activationCountPerInvoker, 1.second)
    res.get(entry.invokerName.toString()) shouldBe Some(2)

    loadBalancerData.putActivation(entrySameInvoker.id, entrySameInvoker)
    Await.result(loadBalancerData.activationCountOn(entry.namespaceId), 1.second) shouldBe 2
    res = Await.result(loadBalancerData.activationCountPerInvoker, 1.second)
    res.get(entry.invokerName.toString()) shouldBe Some(3)

    loadBalancerData.removeActivation(entrySameInvokerAndNamespace)
    Await.result(loadBalancerData.activationCountOn(entry.namespaceId), 1.second) shouldBe 1
    res = Await.result(loadBalancerData.activationCountPerInvoker, 1.second)
    res.get(entry.invokerName.toString()) shouldBe Some(2)

    // removing non existing entry doesn't mess up
    loadBalancerData.removeActivation(entrySameInvokerAndNamespace)
    Await.result(loadBalancerData.activationCountOn(entry.namespaceId), 1.second) shouldBe 1
    res = Await.result(loadBalancerData.activationCountPerInvoker, 1.second)
    res.get(entry.invokerName.toString()) shouldBe Some(2)

    // clean up
    loadBalancerData.removeActivation(entry)
    loadBalancerData.removeActivation(entrySameInvoker.id)
  }

  it should "not add the same entry more then once" in {

    val loadBalancerData = new LoadBalancerData()

    loadBalancerData.putActivation(firstEntry.id, firstEntry)
    val res = Await.result(loadBalancerData.activationCountPerInvoker, 1.second)
    res.get(firstEntry.invokerName.toString()) shouldBe Some(1)
    Await.result(loadBalancerData.activationCountOn(firstEntry.namespaceId), 1.second) shouldBe 1

    loadBalancerData.putActivation(firstEntry.id, firstEntry)
    val resAfterAddingTheSameEntry = Await.result(loadBalancerData.activationCountPerInvoker, 1.second)
    resAfterAddingTheSameEntry.get(firstEntry.invokerName.toString()) shouldBe Some(1)
    Await.result(loadBalancerData.activationCountOn(firstEntry.namespaceId), 1.second) shouldBe 1

    loadBalancerData.removeActivation(firstEntry)
    loadBalancerData.removeActivation(firstEntry)
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

    // clean up after yourself
    loadBalancerData.removeActivation(firstEntry)
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

    val res = Await.result(loadBalancerData.activationCountPerInvoker, 1.second)
    res.get(firstEntry.invokerName.toString()) shouldBe Some(1)
    Await.result(loadBalancerData.activationCountOn(firstEntry.namespaceId), 1.second) shouldBe 1

    // entry already exists, should not evaluate the block and change the state
    val entryAfterSecond = loadBalancerData.putActivation(entrySameId.id, {
      called += 1
      entrySameId
    })

    called shouldBe 1
    entry shouldBe entryAfterSecond
    val resAfterAddingTheSameEntry = Await.result(loadBalancerData.activationCountPerInvoker, 1.second)
    resAfterAddingTheSameEntry.get(firstEntry.invokerName.toString()) shouldBe Some(1)
    Await.result(loadBalancerData.activationCountOn(firstEntry.namespaceId), 1.second) shouldBe 1

  }

}

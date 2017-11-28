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

import akka.actor.ActorSystem
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import common.StreamLogging
import org.scalatest.{FlatSpec, Matchers}
import whisk.core.entity.{ActivationId, UUID, WhiskActivation}
import whisk.core.loadBalancer.{ActivationEntry, DistributedLoadBalancerData, LocalLoadBalancerData}

import scala.concurrent.{Await, Future, Promise}
import whisk.core.entity.InstanceId

import scala.concurrent.duration._

class LoadBalancerDataTests extends FlatSpec with Matchers with StreamLogging {

  val activationIdPromise = Promise[Either[ActivationId, WhiskActivation]]()
  val firstEntry: ActivationEntry = ActivationEntry(ActivationId(), UUID(), InstanceId(0), activationIdPromise)
  val secondEntry: ActivationEntry = ActivationEntry(ActivationId(), UUID(), InstanceId(1), activationIdPromise)

  val port = 2552
  val host = "127.0.0.1"
  val config = ConfigFactory
    .parseString(s"akka.remote.netty.tcp.hostname = $host")
    .withValue("akka.remote.netty.tcp.port", ConfigValueFactory.fromAnyRef(port))
    .withValue("akka.actor.provider", ConfigValueFactory.fromAnyRef("cluster"))
    .withFallback(ConfigFactory.load())

  val actorSystemName = "controller-actor-system"

  implicit val actorSystem = ActorSystem(actorSystemName, config)

  def await[A](f: Future[A], timeout: FiniteDuration = 1.second) = Await.result(f, timeout)

  behavior of "LoadBalancerData"

  it should "return the number of activations for a namespace" in {
    val distributedLoadBalancerData = new DistributedLoadBalancerData()
    val localLoadBalancerData = new LocalLoadBalancerData()
//    test all implementations
    val loadBalancerDataArray = Array(localLoadBalancerData, distributedLoadBalancerData)
    loadBalancerDataArray.map { lbd =>
      lbd.putActivation(firstEntry.id, firstEntry)
      await(lbd.activationCountOn(firstEntry.namespaceId)) shouldBe 1
      await(lbd.activationCountPerInvoker) shouldBe Map(firstEntry.invokerName.toString -> 1)
      lbd.activationById(firstEntry.id) shouldBe Some(firstEntry)

      // clean up after yourself
      lbd.removeActivation(firstEntry.id)
    }
  }

  it should "return the number of activations for each invoker" in {

    val distributedLoadBalancerData = new DistributedLoadBalancerData()
    val localLoadBalancerData = new LocalLoadBalancerData()

    val loadBalancerDataArray = Array(localLoadBalancerData, distributedLoadBalancerData)
    loadBalancerDataArray.map { lbd =>
      lbd.putActivation(firstEntry.id, firstEntry)
      lbd.putActivation(secondEntry.id, secondEntry)

      val res = await(lbd.activationCountPerInvoker)

      res.get(firstEntry.invokerName.toString()) shouldBe Some(1)
      res.get(secondEntry.invokerName.toString()) shouldBe Some(1)

      lbd.activationById(firstEntry.id) shouldBe Some(firstEntry)
      lbd.activationById(secondEntry.id) shouldBe Some(secondEntry)

      // clean up after yourself
      lbd.removeActivation(firstEntry.id)
      lbd.removeActivation(secondEntry.id)
    }

  }

  it should "remove activations and reflect that accordingly" in {

    val distributedLoadBalancerData = new DistributedLoadBalancerData()
    val localLoadBalancerData = new LocalLoadBalancerData()

    val loadBalancerDataArray = Array(localLoadBalancerData, distributedLoadBalancerData)
    loadBalancerDataArray.map { lbd =>
      lbd.putActivation(firstEntry.id, firstEntry)
      val res = await(lbd.activationCountPerInvoker)
      res.get(firstEntry.invokerName.toString()) shouldBe Some(1)

      await(lbd.activationCountOn(firstEntry.namespaceId)) shouldBe 1

      lbd.removeActivation(firstEntry)

      val resAfterRemoval = await(lbd.activationCountPerInvoker)
      resAfterRemoval.get(firstEntry.invokerName.toString()) shouldBe Some(0)

      await(lbd.activationCountOn(firstEntry.namespaceId)) shouldBe 0
      lbd.activationById(firstEntry.id) shouldBe None
    }

  }

  it should "remove activations from all 3 maps by activation id" in {

    val distributedLoadBalancerData = new DistributedLoadBalancerData()
    val localLoadBalancerData = new LocalLoadBalancerData()

    val loadBalancerDataArray = Array(localLoadBalancerData, distributedLoadBalancerData)
    loadBalancerDataArray.map { lbd =>
      lbd.putActivation(firstEntry.id, firstEntry)

      val res = await(lbd.activationCountPerInvoker)
      res.get(firstEntry.invokerName.toString()) shouldBe Some(1)

      lbd.removeActivation(firstEntry.id)

      val resAfterRemoval = await(lbd.activationCountPerInvoker)
      resAfterRemoval.get(firstEntry.invokerName.toString()) shouldBe Some(0)
    }

  }

  it should "return None if the entry doesn't exist when we remove it" in {
    val distributedLoadBalancerData = new DistributedLoadBalancerData()
    val localLoadBalancerData = new LocalLoadBalancerData()

    val loadBalancerDataArray = Array(localLoadBalancerData, distributedLoadBalancerData)
    loadBalancerDataArray.map { lbd =>
      lbd.removeActivation(firstEntry) shouldBe None
    }

  }

  it should "respond with different values accordingly" in {

    val entry = ActivationEntry(ActivationId(), UUID(), InstanceId(1), activationIdPromise)
    val entrySameInvokerAndNamespace = entry.copy(id = ActivationId())
    val entrySameInvoker = entry.copy(id = ActivationId(), namespaceId = UUID())

    val distributedLoadBalancerData = new DistributedLoadBalancerData()
    val localLoadBalancerData = new LocalLoadBalancerData()

    val loadBalancerDataArray = Array(localLoadBalancerData, distributedLoadBalancerData)
    loadBalancerDataArray.map { lbd =>
      lbd.putActivation(entry.id, entry)

      await(lbd.activationCountOn(entry.namespaceId)) shouldBe 1
      var res = await(lbd.activationCountPerInvoker)
      res.get(entry.invokerName.toString()) shouldBe Some(1)

      lbd.putActivation(entrySameInvokerAndNamespace.id, entrySameInvokerAndNamespace)
      await(lbd.activationCountOn(entry.namespaceId)) shouldBe 2
      res = await(lbd.activationCountPerInvoker)
      res.get(entry.invokerName.toString()) shouldBe Some(2)

      lbd.putActivation(entrySameInvoker.id, entrySameInvoker)
      await(lbd.activationCountOn(entry.namespaceId)) shouldBe 2
      res = await(lbd.activationCountPerInvoker)
      res.get(entry.invokerName.toString()) shouldBe Some(3)

      lbd.removeActivation(entrySameInvokerAndNamespace)
      await(lbd.activationCountOn(entry.namespaceId)) shouldBe 1
      res = await(lbd.activationCountPerInvoker)
      res.get(entry.invokerName.toString()) shouldBe Some(2)

      // removing non existing entry doesn't mess up
      lbd.removeActivation(entrySameInvokerAndNamespace)
      await(lbd.activationCountOn(entry.namespaceId)) shouldBe 1
      res = await(lbd.activationCountPerInvoker)
      res.get(entry.invokerName.toString()) shouldBe Some(2)

      // clean up
      lbd.removeActivation(entry)
      lbd.removeActivation(entrySameInvoker.id)
    }

  }

  it should "not add the same entry more then once" in {

    val distributedLoadBalancerData = new DistributedLoadBalancerData()
    val localLoadBalancerData = new LocalLoadBalancerData()

    val loadBalancerDataArray = Array(localLoadBalancerData, distributedLoadBalancerData)
    loadBalancerDataArray.map { lbd =>
      lbd.putActivation(firstEntry.id, firstEntry)
      val res = await(lbd.activationCountPerInvoker)
      res.get(firstEntry.invokerName.toString()) shouldBe Some(1)
      await(lbd.activationCountOn(firstEntry.namespaceId)) shouldBe 1

      lbd.putActivation(firstEntry.id, firstEntry)
      val resAfterAddingTheSameEntry = await(lbd.activationCountPerInvoker)
      resAfterAddingTheSameEntry.get(firstEntry.invokerName.toString()) shouldBe Some(1)
      await(lbd.activationCountOn(firstEntry.namespaceId)) shouldBe 1

      lbd.removeActivation(firstEntry)
      lbd.removeActivation(firstEntry)
    }

  }

  it should "not evaluate the given block if an entry already exists" in {

    val distributedLoadBalancerData = new DistributedLoadBalancerData()
    val localLoadBalancerData = new LocalLoadBalancerData()

    val loadBalancerDataArray = Array(localLoadBalancerData, distributedLoadBalancerData)
    loadBalancerDataArray.map { lbd =>
      var called = 0

      val entry = lbd.putActivation(firstEntry.id, {
        called += 1
        firstEntry
      })

      called shouldBe 1

      // entry already exists, should not evaluate the block
      val entryAfterSecond = lbd.putActivation(firstEntry.id, {
        called += 1
        firstEntry
      })

      called shouldBe 1
      entry shouldBe entryAfterSecond

      // clean up after yourself
      lbd.removeActivation(firstEntry)
    }

  }

  it should "not evaluate the given block even if an entry is different (but has the same id)" in {

    val distributedLoadBalancerData = new DistributedLoadBalancerData()
    val localLoadBalancerData = new LocalLoadBalancerData()

    val loadBalancerDataArray = Array(localLoadBalancerData, distributedLoadBalancerData)
    loadBalancerDataArray.map { lbd =>
      var called = 0
      val entrySameId = secondEntry.copy(id = firstEntry.id)

      val entry = lbd.putActivation(firstEntry.id, {
        called += 1
        firstEntry
      })

      called shouldBe 1

      val res = await(lbd.activationCountPerInvoker)
      res.get(firstEntry.invokerName.toString()) shouldBe Some(1)
      await(lbd.activationCountOn(firstEntry.namespaceId)) shouldBe 1

      // entry already exists, should not evaluate the block and change the state
      val entryAfterSecond = lbd.putActivation(entrySameId.id, {
        called += 1
        entrySameId
      })

      called shouldBe 1
      entry shouldBe entryAfterSecond
      val resAfterAddingTheSameEntry = await(lbd.activationCountPerInvoker)
      resAfterAddingTheSameEntry.get(firstEntry.invokerName.toString()) shouldBe Some(1)
      await(lbd.activationCountOn(firstEntry.namespaceId)) shouldBe 1
    }

  }

}

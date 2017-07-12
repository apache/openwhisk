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

import java.time.{Clock, Instant}

import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpec, Matchers}
import whisk.core.entity.{ActivationId, UUID, WhiskActivation}
import whisk.core.loadBalancer.{ActivationEntry, LoadBalancerData}

import scala.concurrent.duration.{FiniteDuration, _}
import scala.concurrent.{Await, Future, Promise}



class LoadBalancerDataSpec extends FlatSpec with Matchers with BeforeAndAfterAll with BeforeAndAfterEach{
  def await[A](f: Future[A], timeout: FiniteDuration = 500.milliseconds) = Await.result[A](f,
    timeout)

  var firstActivationEntry :ActivationEntry = null
  var secondActivationEntry :ActivationEntry = null
  var loadBalancerData :LoadBalancerData = null
  val firstUUID :UUID = UUID()
  val secondUUID = UUID()

  val activationIdPromise = Promise[Either[ActivationId, WhiskActivation]]()
  var firstActivationId :ActivationId = ActivationId()
  var secondActivationId :ActivationId = ActivationId()

  override def beforeEach() = {
    loadBalancerData = new LoadBalancerData()
    firstActivationEntry = new ActivationEntry(firstActivationId, firstUUID, "invoker0",
      Instant.now(Clock.systemUTC()), activationIdPromise)
    secondActivationEntry = new ActivationEntry(secondActivationId, secondUUID, "invoker1",
      Instant.now(Clock.systemUTC()), activationIdPromise)
  }

  behavior of "LoadBalancerData"

  it should "return the number of activations for a namespace" in {

    loadBalancerData.putActivation(firstActivationEntry)

    val result = loadBalancerData.activationCountByNamespace
    result.size should be (1)
    result.keys.head should be (firstUUID)
    loadBalancerData.activationsByNamespaceId(firstUUID).toMap.size should be (1)
    loadBalancerData.activationsByInvoker("invoker0").toMap.size should be (1)
    loadBalancerData.activationById(firstActivationId).size should be (1)
  }

  it should "return the number of activations for each invoker" in {

    loadBalancerData.putActivation(firstActivationEntry)
    loadBalancerData.putActivation(secondActivationEntry)

    val result = loadBalancerData.activationCountByInvoker

    result.size should be (2)

    loadBalancerData.activationsByInvoker("invoker0").size should be (1)
    loadBalancerData.activationsByInvoker("invoker1").size should be (1)
    loadBalancerData.activationById(firstActivationId).size should be (1)
    loadBalancerData.activationById(secondActivationId).size should be (1)
  }

  it should "remove activations from all 3 maps" in {

    loadBalancerData.putActivation(firstActivationEntry)
    loadBalancerData.putActivation(secondActivationEntry)

    loadBalancerData.removeActivation(firstActivationEntry)
    loadBalancerData.removeActivation(secondActivationEntry)

    val activationsByInvoker = loadBalancerData.activationCountByInvoker
    val activationsByNamespace = loadBalancerData.activationCountByNamespace


    activationsByInvoker.values.sum should be (0)
    activationsByNamespace.values.sum should be (0)
    loadBalancerData.activationById(firstActivationId).size should be (0)
    loadBalancerData.activationById(secondActivationId).size should be (0)
  }
  it should "remove activations from all 3 maps by activation id" in {

    loadBalancerData.putActivation(firstActivationEntry)

    loadBalancerData.removeActivation(firstActivationId)

    val activationsByInvoker = loadBalancerData.activationCountByInvoker
    val activationsByNamespace = loadBalancerData.activationCountByNamespace

    activationsByInvoker.values.sum should be (0)
    activationsByNamespace.values.sum should be (0)
    loadBalancerData.activationById(firstActivationId).size should be (0)

  }


}

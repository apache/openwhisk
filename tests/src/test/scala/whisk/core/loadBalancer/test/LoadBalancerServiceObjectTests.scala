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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import whisk.core.loadBalancer.LoadBalancerService
import whisk.core.loadBalancer.Healthy
import whisk.core.loadBalancer.Offline
import whisk.core.loadBalancer.UnHealthy
import whisk.core.entity.InstanceId

/**
 * Unit tests for the ContainerPool object.
 *
 * These tests test only the "static" methods "schedule" and "remove"
 * of the ContainerPool object.
 */
@RunWith(classOf[JUnitRunner])
class LoadBalancerServiceObjectTests extends FlatSpec with Matchers {
  behavior of "memoize"

  it should "not recompute a value which was already given" in {
    var calls = 0
    val add1: Int => Int = LoadBalancerService.memoize {
      case second =>
        calls += 1
        1 + second
    }

    add1(1) shouldBe 2
    calls shouldBe 1
    add1(1) shouldBe 2
    calls shouldBe 1
    add1(2) shouldBe 3
    calls shouldBe 2
    add1(1) shouldBe 2
    calls shouldBe 2
  }

  behavior of "pairwiseCoprimeNumbersUntil"

  it should "return an empty set for malformed inputs" in {
    LoadBalancerService.pairwiseCoprimeNumbersUntil(0) shouldBe Seq()
    LoadBalancerService.pairwiseCoprimeNumbersUntil(-1) shouldBe Seq()
  }

  it should "return all coprime numbers until the number given" in {
    LoadBalancerService.pairwiseCoprimeNumbersUntil(1) shouldBe Seq(1)
    LoadBalancerService.pairwiseCoprimeNumbersUntil(2) shouldBe Seq(1)
    LoadBalancerService.pairwiseCoprimeNumbersUntil(3) shouldBe Seq(1, 2)
    LoadBalancerService.pairwiseCoprimeNumbersUntil(4) shouldBe Seq(1, 3)
    LoadBalancerService.pairwiseCoprimeNumbersUntil(5) shouldBe Seq(1, 2, 3)
    LoadBalancerService.pairwiseCoprimeNumbersUntil(9) shouldBe Seq(1, 2, 5, 7)
    LoadBalancerService.pairwiseCoprimeNumbersUntil(10) shouldBe Seq(1, 3, 7)
  }

  behavior of "chooseInvoker"

  def invokers(n: Int) = (0 until n).map(i => (InstanceId(i), Healthy, 0))
  def hashInto[A](list: Seq[A], hash: Int) = list(hash % list.size)

  it should "return None on an empty invokers list" in {
    LoadBalancerService.schedule(IndexedSeq(), 0, 1) shouldBe None
  }

  it should "return None on a list of offline/unhealthy invokers" in {
    val invs = IndexedSeq((InstanceId(0), Offline, 0), (InstanceId(1), UnHealthy, 0))

    LoadBalancerService.schedule(invs, 0, 1) shouldBe None
  }

  it should "schedule to the home invoker" in {
    val invs = invokers(10)
    val hash = 2

    LoadBalancerService.schedule(invs, 1, hash) shouldBe Some(InstanceId(hash % invs.size))
  }

  it should "take the only online invoker" in {
    LoadBalancerService.schedule(
      IndexedSeq((InstanceId(0), Offline, 0), (InstanceId(1), UnHealthy, 0), (InstanceId(2), Healthy, 0)),
      0,
      1) shouldBe Some(InstanceId(2))
  }

  it should "skip an offline/unhealthy invoker, even if its underloaded" in {
    val hash = 0
    val invs = IndexedSeq((InstanceId(0), Healthy, 10), (InstanceId(1), UnHealthy, 0), (InstanceId(2), Healthy, 0))

    LoadBalancerService.schedule(invs, 10, hash) shouldBe Some(InstanceId(2))
  }

  it should "jump to the next invoker determined by a hashed stepsize if the home invoker is overloaded" in {
    val invokerCount = 10
    val hash = 2
    val targetInvoker = hash % invokerCount

    val invs = invokers(invokerCount).updated(targetInvoker, (InstanceId(targetInvoker), Healthy, 1))
    val step = hashInto(LoadBalancerService.pairwiseCoprimeNumbersUntil(invokerCount), hash)

    LoadBalancerService.schedule(invs, 1, hash) shouldBe Some(InstanceId((hash + step) % invs.size))
  }

  it should "wrap the search at the end of the invoker list" in {
    val invokerCount = 3
    val invs = IndexedSeq((InstanceId(0), Healthy, 1), (InstanceId(1), Healthy, 1), (InstanceId(2), Healthy, 0))
    val hash = 1

    val targetInvoker = hashInto(invs, hash) // will be invoker1
    val step = hashInto(LoadBalancerService.pairwiseCoprimeNumbersUntil(invokerCount), hash) // will be 2
    step shouldBe 2

    // invoker1 is overloaded so it will step (2 steps) to the next one --> 1 2 0 --> invoker0 is next target
    // invoker0 is overloaded so it will step to the next one --> 0 1 2 --> invoker2 is next target and underloaded
    LoadBalancerService.schedule(invs, 1, hash) shouldBe Some(InstanceId((hash + step + step) % invs.size))
  }

  it should "multiply its threshold in 3 iterations to find an invoker with a good warm-chance" in {
    val invs = IndexedSeq((InstanceId(0), Healthy, 33), (InstanceId(1), Healthy, 36), (InstanceId(2), Healthy, 33))
    val hash = 0 // home is 0, stepsize is 1

    // even though invoker1 is not the home invoker in this case, it gets chosen over
    // the others because it's the first one encountered by the iteration mechanism to be below
    // the threshold of 3 * 16 invocations
    LoadBalancerService.schedule(invs, 16, hash) shouldBe Some(InstanceId(0))
  }

  it should "choose the home invoker if all invokers are overloaded even above the muliplied threshold" in {
    val invs = IndexedSeq((InstanceId(0), Healthy, 51), (InstanceId(1), Healthy, 50), (InstanceId(2), Healthy, 49))
    val hash = 0 // home is 0, stepsize is 1

    LoadBalancerService.schedule(invs, 16, hash) shouldBe Some(InstanceId(0))
  }

  it should "transparently work with partitioned sets of invokers" in {
    val invs = IndexedSeq((InstanceId(3), Healthy, 0), (InstanceId(4), Healthy, 0), (InstanceId(5), Healthy, 0))

    LoadBalancerService.schedule(invs, 1, 0) shouldBe Some(InstanceId(3))
    LoadBalancerService.schedule(invs, 1, 1) shouldBe Some(InstanceId(4))
    LoadBalancerService.schedule(invs, 1, 2) shouldBe Some(InstanceId(5))
    LoadBalancerService.schedule(invs, 1, 3) shouldBe Some(InstanceId(3))
  }
}

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

    def invokers(n: Int) = (0 until n).map(i => s"invoker$i")
    def hashInto[A](list: Seq[A], hash: Int) = list(hash % list.size)

    it should "return None on an empty invokers list" in {
        LoadBalancerService.schedule(Seq(), Map(), 0, 1) shouldBe None
    }

    it should "schedule to the home invoker" in {
        val invs = invokers(10)
        val hash = 2

        LoadBalancerService.schedule(invs, Map(), 1, hash) shouldBe Some(hashInto(invs, hash))
    }

    it should "jump to the next invoker determined by a hashed stepsize if the home invoker is overloaded" in {
        val invokerCount = 10
        val invs = invokers(invokerCount)
        val hash = 2

        val targetInvoker = hashInto(invs, hash)
        val step = hashInto(LoadBalancerService.pairwiseCoprimeNumbersUntil(invokerCount), hash)

        LoadBalancerService.schedule(invs, Map(targetInvoker -> 1), 1, hash) shouldBe Some(hashInto(invs, hash + step))
    }

    it should "wrap the search at the end of the invoker list" in {
        val invokerCount = 3
        val invs = invokers(invokerCount)
        val hash = 1

        val targetInvoker = hashInto(invs, hash) // will be invoker1
        val step = hashInto(LoadBalancerService.pairwiseCoprimeNumbersUntil(invokerCount), hash) // will be 2
        step shouldBe 2

        // invoker1 is overloaded so it will step (2 steps) to the next one --> 1 2 0 --> invoker0 is next target
        // invoker0 is overloaded so it will step to the next one --> 0 1 2 --> invoker2 is next target and underloaded
        LoadBalancerService.schedule(
            invs,
            Map("invoker0" -> 1, "invoker1" -> 1),
            1, hash) shouldBe Some(hashInto(invs, hash + step + step))
    }

    it should "multiply its threshold in 3 iterations to find an invoker with a good warm-chance" in {
        val invokerCount = 3
        val invs = invokers(invokerCount)
        val hash = 0 // home is 0, stepsize is 1

        // even though invoker1 is not the home invoker in this case, it gets chosen over
        // the others because it's the first one encountered by the iteration mechanism to be below
        // the threshold of 3 * 16 invocations
        LoadBalancerService.schedule(
            invs,
            Map("invoker0" -> 33, "invoker1" -> 36, "invoker2" -> 33),
            16,
            hash) shouldBe Some("invoker0")
    }

    it should "choose the home invoker if all invokers are overloaded even above the muliplied threshold" in {
        val invokerCount = 3
        val invs = invokers(invokerCount)
        val hash = 0 // home is 0, stepsize is 1

        LoadBalancerService.schedule(
            invs,
            Map("invoker0" -> 51, "invoker1" -> 50, "invoker2" -> 49),
            16,
            hash) shouldBe Some("invoker0")
    }
}

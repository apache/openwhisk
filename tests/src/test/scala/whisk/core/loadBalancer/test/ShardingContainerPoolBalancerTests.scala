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

import common.StreamLogging
import org.junit.runner.RunWith
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.junit.JUnitRunner
import whisk.common.{ForcibleSemaphore, TransactionId}
import whisk.core.entity.InvokerInstanceId
import whisk.core.loadBalancer._
import whisk.core.loadBalancer.InvokerState._

/**
 * Unit tests for the ContainerPool object.
 *
 * These tests test only the "static" methods "schedule" and "remove"
 * of the ContainerPool object.
 */
@RunWith(classOf[JUnitRunner])
class ShardingContainerPoolBalancerTests extends FlatSpec with Matchers with StreamLogging {
  behavior of "ShardingContainerPoolBalancerState"

  def healthy(i: Int) = new InvokerHealth(InvokerInstanceId(i), Healthy)
  def unhealthy(i: Int) = new InvokerHealth(InvokerInstanceId(i), Unhealthy)
  def offline(i: Int) = new InvokerHealth(InvokerInstanceId(i), Offline)

  def semaphores(count: Int, max: Int): IndexedSeq[ForcibleSemaphore] =
    IndexedSeq.fill(count)(new ForcibleSemaphore(max))

  def lbConfig(blackboxFraction: Double, invokerBusyThreshold: Int) =
    ShardingContainerPoolBalancerConfig(blackboxFraction, invokerBusyThreshold, 1)

  it should "update invoker's state, growing the slots data and keeping valid old data" in {
    // start empty
    val slots = 10
    val state = ShardingContainerPoolBalancerState()(lbConfig(0.5, slots))
    state.invokers shouldBe 'empty
    state.blackboxInvokers shouldBe 'empty
    state.managedInvokers shouldBe 'empty
    state.invokerSlots shouldBe 'empty
    state.managedStepSizes shouldBe Seq.empty
    state.blackboxStepSizes shouldBe Seq.empty

    // apply one update, verify everything is updated accordingly
    val update1 = IndexedSeq(healthy(0))
    state.updateInvokers(update1)

    state.invokers shouldBe update1
    state.blackboxInvokers shouldBe update1 // fallback to at least one
    state.managedInvokers shouldBe update1 // fallback to at least one
    state.invokerSlots should have size update1.size
    state.invokerSlots.head.availablePermits shouldBe slots
    state.managedStepSizes shouldBe Seq(1)
    state.blackboxStepSizes shouldBe Seq(1)

    // aquire a slot to alter invoker state
    state.invokerSlots.head.tryAcquire()
    state.invokerSlots.head.availablePermits shouldBe slots - 1

    // apply second update, growing the state
    val update2 = IndexedSeq(healthy(0), healthy(1))
    state.updateInvokers(update2)

    state.invokers shouldBe update2
    state.managedInvokers shouldBe IndexedSeq(update2.head)
    state.blackboxInvokers shouldBe IndexedSeq(update2.last)
    state.invokerSlots should have size update2.size
    state.invokerSlots.head.availablePermits shouldBe slots - 1
    state.invokerSlots(1).availablePermits shouldBe slots
    state.managedStepSizes shouldBe Seq(1)
    state.blackboxStepSizes shouldBe Seq(1)
  }

  it should "allow managed partition to overlap with blackbox for small N" in {
    Seq(0.1, 0.2, 0.3, 0.4, 0.5).foreach { bf =>
      val state = ShardingContainerPoolBalancerState()(lbConfig(bf, 1))

      (1 to 100).toSeq.foreach { i =>
        state.updateInvokers((1 to i).map(_ => healthy(1)))

        withClue(s"invoker count $bf $i:") {
          state.managedInvokers.length should be <= i
          state.blackboxInvokers should have size Math.max(1, (bf * i).toInt)

          val m = state.managedInvokers.length
          val b = state.blackboxInvokers.length
          bf match {
            // written out explicitly for clarity
            case 0.1 if i < 10 => m + b shouldBe i + 1
            case 0.2 if i < 5  => m + b shouldBe i + 1
            case 0.3 if i < 4  => m + b shouldBe i + 1
            case 0.4 if i < 3  => m + b shouldBe i + 1
            case 0.5 if i < 2  => m + b shouldBe i + 1
            case _             => m + b shouldBe i
          }
        }
      }
    }
  }

  it should "update the cluster size, adjusting the invoker slots accordingly" in {
    val slots = 10
    val state = ShardingContainerPoolBalancerState()(lbConfig(0.5, slots))
    state.updateInvokers(IndexedSeq(healthy(0)))

    state.invokerSlots.head.tryAcquire()
    state.invokerSlots.head.availablePermits shouldBe slots - 1

    state.updateCluster(2)
    state.invokerSlots.head.availablePermits shouldBe slots / 2 // state reset + divided by 2
  }

  it should "fallback to a size of 1 (alone) if cluster size is < 1" in {
    val slots = 10
    val state = ShardingContainerPoolBalancerState()(lbConfig(0.5, slots))
    state.updateInvokers(IndexedSeq(healthy(0)))

    state.invokerSlots.head.availablePermits shouldBe slots

    state.updateCluster(2)
    state.invokerSlots.head.availablePermits shouldBe slots / 2

    state.updateCluster(0)
    state.invokerSlots.head.availablePermits shouldBe slots

    state.updateCluster(-1)
    state.invokerSlots.head.availablePermits shouldBe slots
  }

  it should "set the threshold to 1 if the cluster is bigger than there are slots on 1 invoker" in {
    val slots = 10
    val state = ShardingContainerPoolBalancerState()(lbConfig(0.5, slots))
    state.updateInvokers(IndexedSeq(healthy(0)))

    state.invokerSlots.head.availablePermits shouldBe slots

    state.updateCluster(20)

    state.invokerSlots.head.availablePermits shouldBe 1
  }

  behavior of "schedule"

  implicit val transId = TransactionId.testing

  it should "return None on an empty invoker list" in {
    ShardingContainerPoolBalancer.schedule(IndexedSeq.empty, IndexedSeq.empty, index = 0, step = 2) shouldBe None
  }

  it should "return None if no invokers are healthy" in {
    val invokerCount = 3
    val invokerSlots = semaphores(invokerCount, 3)
    val invokers = (0 until invokerCount).map(unhealthy)

    ShardingContainerPoolBalancer.schedule(invokers, invokerSlots, index = 0, step = 2) shouldBe None
  }

  it should "choose the first available invoker, jumping in stepSize steps, falling back to randomized scheduling once all invokers are full" in {
    val invokerCount = 3
    val invokerSlots = semaphores(invokerCount + 3, 3) // needs to be offset by 3 as well
    val invokers = (0 until invokerCount).map(i => healthy(i + 3)) // offset by 3 to asset InstanceId is returned

    val expectedResult = Seq(3, 3, 3, 5, 5, 5, 4, 4, 4)
    val result = expectedResult.map { _ =>
      ShardingContainerPoolBalancer.schedule(invokers, invokerSlots, index = 0, step = 2).get.toInt
    }

    result shouldBe expectedResult

    val bruteResult = (0 to 100).map { _ =>
      ShardingContainerPoolBalancer.schedule(invokers, invokerSlots, index = 0, step = 2).get.toInt
    }

    bruteResult should contain allOf (3, 4, 5)
  }

  it should "ignore unhealthy or offline invokers" in {
    val invokers = IndexedSeq(healthy(0), unhealthy(1), offline(2), healthy(3))
    val invokerSlots = semaphores(invokers.size, 3)

    val expectedResult = Seq(0, 0, 0, 3, 3, 3)
    val result = expectedResult.map { _ =>
      ShardingContainerPoolBalancer.schedule(invokers, invokerSlots, index = 0, step = 1).get.toInt
    }

    result shouldBe expectedResult

    // more schedules will result in randomized invokers, but the unhealthy and offline invokers should not be part
    val bruteResult = (0 to 100).map { _ =>
      ShardingContainerPoolBalancer.schedule(invokers, invokerSlots, index = 0, step = 1).get.toInt
    }

    bruteResult should contain allOf (0, 3)
    bruteResult should contain noneOf (1, 2)
  }

  behavior of "pairwiseCoprimeNumbersUntil"

  it should "return an empty set for malformed inputs" in {
    ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(0) shouldBe Seq.empty
    ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(-1) shouldBe Seq.empty
  }

  it should "return all coprime numbers until the number given" in {
    ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(1) shouldBe Seq(1)
    ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(2) shouldBe Seq(1)
    ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(3) shouldBe Seq(1, 2)
    ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(4) shouldBe Seq(1, 3)
    ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(5) shouldBe Seq(1, 2, 3)
    ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(9) shouldBe Seq(1, 2, 5, 7)
    ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(10) shouldBe Seq(1, 3, 7)
  }
}

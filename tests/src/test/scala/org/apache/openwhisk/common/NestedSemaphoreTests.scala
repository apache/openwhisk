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

package org.apache.openwhisk.common

import common.ConcurrencyHelpers
import org.apache.openwhisk.utils.ExecutionContextFactory
import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration.DurationInt

@RunWith(classOf[JUnitRunner])
class NestedSemaphoreTests extends FlatSpec with Matchers with ConcurrencyHelpers {
  // use an infinite thread pool to allow for maximum concurrency
  implicit val executionContext = ExecutionContextFactory.makeCachedThreadPoolExecutionContext()
  val acquireTimeout = 1.minute

  behavior of "NestedSemaphore"

  it should "allow acquire of concurrency permits before acquire of memory permits" in {
    val s = new NestedSemaphore[String](20)
    s.availablePermits shouldBe 20

    val actionId = "action1"
    val actionConcurrency = 5
    val actionMemory = 3
    //use all concurrency on a single slot
    concurrently(5, acquireTimeout) {
      s.tryAcquireConcurrent(actionId, actionConcurrency, actionMemory)
    } should contain only true
    s.availablePermits shouldBe 20 - 3 //we used a single container (memory == 3)
    s.concurrentState(actionId).availablePermits shouldBe 0

    //use up all the remaining memory (17) and concurrency slots (17 / 3 * 5 = 25)
    concurrently(25, acquireTimeout) {
      s.tryAcquireConcurrent(actionId, actionConcurrency, actionMemory)
    } should contain only true

    s.availablePermits shouldBe 2 //we used 18 (20/3 = 6, 6*3=18)
    s.concurrentState(actionId).availablePermits shouldBe 0
    s.tryAcquireConcurrent("action1", actionConcurrency, actionMemory) shouldBe false

  }

  it should "not give away more permits even under concurrent load" in {
    // 100 iterations of this test
    (0 until 100).foreach { _ =>
      val s = new NestedSemaphore(32)
      // try to acquire more permits than allowed in parallel
      val acquires = concurrently(64, acquireTimeout)(s.tryAcquire())

      val result = Seq.fill(32)(true) ++ Seq.fill(32)(false)
      acquires should contain theSameElementsAs result
    }
  }
}

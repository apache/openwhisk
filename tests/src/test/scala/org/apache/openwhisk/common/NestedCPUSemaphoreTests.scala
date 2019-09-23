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

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class NestedCPUSemaphoreTests extends FlatSpec with Matchers {
  behavior of "NestedCPUSemaphoreTests"

  it should "allow acquire of concurrency permits before acquire of CPU permits" in {
    val s = new NestedCPUSemaphore[String](1.toFloat)
    s.availablePermits shouldBe 100 // We only accept this unit currently

    val actionId = "action1"
    val actionConcurrency = 5
    val actionCPU = CPULimitUtils.threadsToPermits(0.06)
    //use all concurrency on a single slot
    (1 to 5).par.map { _ =>
      s.tryAcquireConcurrent(actionId, actionConcurrency, actionCPU) shouldBe true
    }
    s.availablePermits shouldBe 100 - 6 //we used a single container (CPU permits == 6)
    s.concurrentState(actionId).availablePermits shouldBe 0

    //use up all the remaining CPU permits (94) and concurrency slots (94 / 6 * 5 = 75)
    (1 to 75).par.map { _ =>
      s.tryAcquireConcurrent(actionId, actionConcurrency, actionCPU) shouldBe true
    }
    s.availablePermits shouldBe 4 //we used 96 (100/6 = 16, 16*6 = 96)
    s.concurrentState(actionId).availablePermits shouldBe 0
    s.tryAcquireConcurrent("action1", actionConcurrency, actionCPU) shouldBe false
  }

  it should "not give away more permits even under concurrent load" in {
    // 100 iterations of this test
    (0 until 100).foreach { _ =>
      val s = new NestedCPUSemaphore((0.32).toFloat)
      // try to acquire more permits than allowed in parallel
      val acquires = (0 until 64).par.map(_ => s.tryAcquire()).seq

      val result = Seq.fill(32)(true) ++ Seq.fill(32)(false)
      acquires should contain theSameElementsAs result
    }
  }
}

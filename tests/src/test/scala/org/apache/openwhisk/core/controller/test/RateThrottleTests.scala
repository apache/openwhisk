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

package org.apache.openwhisk.core.controller.test

import scala.concurrent.duration.DurationInt

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import common.StreamLogging
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.entitlement._
import org.apache.openwhisk.core.entity.UserLimits

/**
 * Tests rate throttle.
 *
 * @Idioglossia
 * "using Specification DSL to write unit tests, as in should, must, not, be"
 */
@RunWith(classOf[JUnitRunner])
class RateThrottleTests extends FlatSpec with Matchers with StreamLogging {

  implicit val transid = TransactionId.testing
  val subject = WhiskAuthHelpers.newIdentity()

  behavior of "Rate Throttle"

  it should "throttle when rate exceeds allowed threshold" in {
    new RateThrottler("test", _ => 0).check(subject).ok shouldBe false
    val rt = new RateThrottler("test", _ => 1)
    rt.check(subject).ok shouldBe true
    rt.check(subject).ok shouldBe false
    rt.check(subject).ok shouldBe false
    Thread.sleep(1.minute.toMillis)
    rt.check(subject).ok shouldBe true
  }

  it should "check against an alternative limit if passed in" in {
    val withLimits = subject.copy(limits = UserLimits(invocationsPerMinute = Some(5)))
    val rt = new RateThrottler("test", u => u.limits.invocationsPerMinute.getOrElse(1))
    rt.check(withLimits).ok shouldBe true // 1
    rt.check(withLimits).ok shouldBe true // 2
    rt.check(withLimits).ok shouldBe true // 3
    rt.check(withLimits).ok shouldBe true // 4
    rt.check(withLimits).ok shouldBe true // 5
    rt.check(withLimits).ok shouldBe false
  }

}

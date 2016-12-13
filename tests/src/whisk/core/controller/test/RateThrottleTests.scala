/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.controller.test

import scala.concurrent.duration.DurationInt

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import whisk.common.TransactionId
import whisk.core.entitlement._
import whisk.core.entity.Subject

/**
 * Tests rate throttle.
 *
 * @Idioglossia
 * "using Specification DSL to write unit tests, as in should, must, not, be"
 */
@RunWith(classOf[JUnitRunner])
class RateThrottleTests extends FlatSpec with Matchers {
    implicit val transid = TransactionId.testing
    val subject = Subject()

    behavior of "Rate Throttle"

    it should "throttle when rate exceeds allowed threshold" in {
        new RateThrottler("test", 0).check(subject) shouldBe false
        val rt = new RateThrottler("test", 1)
        rt.check(subject) shouldBe true
        rt.check(subject) shouldBe false
        rt.check(subject) shouldBe false
        Thread.sleep(1.minute.toMillis)
        rt.check(subject) shouldBe true
    }

}

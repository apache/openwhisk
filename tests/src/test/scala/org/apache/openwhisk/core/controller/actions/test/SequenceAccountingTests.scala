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

package org.apache.openwhisk.core.controller.actions.test

import java.time.Instant

import scala.concurrent.duration.DurationInt

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import common.WskActorSystem
import spray.json._
import org.apache.openwhisk.core.controller.actions.SequenceAccounting
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.ActivationResponse
import org.apache.openwhisk.core.entity.size.SizeInt
import org.apache.openwhisk.http.Messages

@RunWith(classOf[JUnitRunner])
class SequenceAccountingTests extends FlatSpec with Matchers with WskActorSystem {

  behavior of "sequence accounting"

  val okRes1 = ActivationResponse.success(Some(JsObject("res" -> JsNumber(1))))
  val okRes2 = ActivationResponse.success(Some(JsObject("res" -> JsNumber(2))))
  val failedRes = ActivationResponse.applicationError(JsNumber(3))

  val okActivation = WhiskActivation(
    namespace = EntityPath("ns"),
    name = EntityName("a"),
    Subject(),
    activationId = ActivationId.generate(),
    start = Instant.now(),
    end = Instant.now(),
    response = okRes2,
    annotations = Parameters("limits", ActionLimits(TimeLimit(1.second), MemoryLimit(128.MB), LogLimit(1.MB)).toJson),
    duration = Some(123))

  val notOkActivation = WhiskActivation(
    namespace = EntityPath("ns"),
    name = EntityName("a"),
    Subject(),
    activationId = ActivationId.generate(),
    start = Instant.now(),
    end = Instant.now(),
    response = failedRes,
    annotations = Parameters("limits", ActionLimits(TimeLimit(11.second), MemoryLimit(256.MB), LogLimit(2.MB)).toJson),
    duration = Some(234))

  it should "create initial accounting object" in {
    val s = SequenceAccounting(2, okRes1)
    s.atomicActionCnt shouldBe 2
    s.previousResponse.get shouldBe okRes1
    s.logs shouldBe empty
    s.duration shouldBe 0
    s.maxMemory shouldBe None
    s.shortcircuit shouldBe false
  }

  it should "resolve maybe to success and update accounting object" in {
    val p = SequenceAccounting(2, okRes1)
    val n1 = p.maybe(okActivation, 3, 5)
    n1.atomicActionCnt shouldBe 3
    n1.previousResponse.get shouldBe okRes2
    n1.logs.length shouldBe 1
    n1.logs(0) shouldBe okActivation.activationId
    n1.duration shouldBe 123
    n1.maxMemory shouldBe Some(128)
    n1.shortcircuit shouldBe false
  }

  it should "resolve maybe and enable short circuit" in {
    val p = SequenceAccounting(2, okRes1)
    val n1 = p.maybe(okActivation, 3, 5)
    val n2 = n1.maybe(notOkActivation, 4, 5)
    n2.atomicActionCnt shouldBe 4
    n2.previousResponse.get shouldBe failedRes
    n2.logs.length shouldBe 2
    n2.logs(0) shouldBe okActivation.activationId
    n2.logs(1) shouldBe notOkActivation.activationId
    n2.duration shouldBe (123 + 234)
    n2.maxMemory shouldBe Some(256)
    n2.shortcircuit shouldBe true
  }

  it should "record an activation that exceeds allowed limit but also short circuit" in {
    val p = SequenceAccounting(2, okRes1)
    val n = p.maybe(okActivation, 3, 2)
    n.atomicActionCnt shouldBe 3
    n.previousResponse.get shouldBe ActivationResponse.applicationError(Messages.sequenceIsTooLong)
    n.logs.length shouldBe 1
    n.logs(0) shouldBe okActivation.activationId
    n.duration shouldBe 123
    n.maxMemory shouldBe Some(128)
    n.shortcircuit shouldBe true
  }

  it should "set failed response and short circuit on failure" in {
    val p = SequenceAccounting(2, okRes1)
    val n = p.maybe(okActivation, 3, 3)
    val f = n.fail(failedRes, None)
    f.atomicActionCnt shouldBe 3
    f.previousResponse.get shouldBe failedRes
    f.logs.length shouldBe 1
    f.logs(0) shouldBe okActivation.activationId
    f.duration shouldBe 123
    f.maxMemory shouldBe Some(128)
    f.shortcircuit shouldBe true
  }

  it should "resolve max memory" in {
    SequenceAccounting.maxMemory(None, None) shouldBe None
    SequenceAccounting.maxMemory(None, Some(1)) shouldBe Some(1)
    SequenceAccounting.maxMemory(Some(1), None) shouldBe Some(1)
    SequenceAccounting.maxMemory(Some(1), Some(2)) shouldBe Some(2)
    SequenceAccounting.maxMemory(Some(2), Some(1)) shouldBe Some(2)
    SequenceAccounting.maxMemory(Some(2), Some(2)) shouldBe Some(2)
  }
}

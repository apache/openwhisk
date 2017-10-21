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

package whisk.core.connector.tests

import java.time.Instant

import scala.util.Success
import scala.concurrent.duration.DurationInt

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import spray.json._
import whisk.common.TransactionId
import whisk.core.connector.CompletionMessage
import whisk.core.entity._
import whisk.core.entity.size.SizeInt

/**
 * Unit tests for the CompletionMessage object.
 */
@RunWith(classOf[JUnitRunner])
class CompletionMessageTests extends FlatSpec with Matchers {

  behavior of "completion message"

  val activation = WhiskActivation(
    namespace = EntityPath("ns"),
    name = EntityName("a"),
    Subject(),
    activationId = ActivationId(),
    start = Instant.now(),
    end = Instant.now(),
    response = ActivationResponse.success(Some(JsObject("res" -> JsNumber(1)))),
    annotations = Parameters("limits", ActionLimits(TimeLimit(1.second), MemoryLimit(128.MB), LogLimit(1.MB)).toJson),
    duration = Some(123))

  it should "serialize a left completion message" in {
    val m = CompletionMessage(TransactionId.testing, Left(ActivationId()), InstanceId(0))
    m.serialize shouldBe JsObject(
      "transid" -> m.transid.toJson,
      "response" -> m.response.left.get.toJson,
      "invoker" -> m.invoker.toJson).compactPrint
  }

  it should "serialize a right completion message" in {
    val m = CompletionMessage(TransactionId.testing, Right(activation), InstanceId(0))
    m.serialize shouldBe JsObject(
      "transid" -> m.transid.toJson,
      "response" -> m.response.right.get.toJson,
      "invoker" -> m.invoker.toJson).compactPrint
  }

  it should "deserialize a left completion message" in {
    val m = CompletionMessage(TransactionId.testing, Left(ActivationId()), InstanceId(0))
    CompletionMessage.parse(m.serialize) shouldBe Success(m)
  }

  it should "deserialize a right completion message" in {
    val m = CompletionMessage(TransactionId.testing, Right(activation), InstanceId(0))
    CompletionMessage.parse(m.serialize) shouldBe Success(m)
  }
}

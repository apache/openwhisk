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

package org.apache.openwhisk.core.connector.tests

import org.junit.runner.RunWith
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.junit.JUnitRunner
import spray.json._
import org.apache.openwhisk.common.{TransactionId, WhiskInstants}
import org.apache.openwhisk.core.connector.{AcknowledegmentMessage, CompletionMessage, ResultMessage}
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size.SizeInt

import scala.concurrent.duration.DurationInt
import scala.util.Success

/**
 * Unit tests for the AcknowledgementMessageTests object.
 */
@RunWith(classOf[JUnitRunner])
class AcknowledgementMessageTests extends FlatSpec with Matchers with WhiskInstants {

  behavior of "result message"

  val defaultUserMemory: ByteSize = 1024.MB
  val activation = WhiskActivation(
    namespace = EntityPath("ns"),
    name = EntityName("a"),
    Subject(),
    activationId = ActivationId.generate(),
    start = nowInMillis(),
    end = nowInMillis(),
    response = ActivationResponse.success(Some(JsObject("res" -> JsNumber(1)))),
    annotations = Parameters("limits", ActionLimits(TimeLimit(1.second), MemoryLimit(128.MB), LogLimit(1.MB)).toJson),
    duration = Some(123))

  it should "serialize a left result message" in {
    val m = ResultMessage(TransactionId.testing, Left(ActivationId.generate()))
    m.serialize shouldBe JsObject("transid" -> m.transid.toJson, "response" -> m.response.left.get.toJson).compactPrint
  }

  it should "serialize a right result message" in {
    val m =
      ResultMessage(TransactionId.testing, Right(activation))
    m.serialize shouldBe JsObject("transid" -> m.transid.toJson, "response" -> m.response.right.get.toJson).compactPrint
  }

  it should "deserialize a left result message" in {
    val m = ResultMessage(TransactionId.testing, Left(ActivationId.generate()))
    ResultMessage.parse(m.serialize) shouldBe Success(m)
  }

  it should "deserialize a right result message" in {
    val m =
      ResultMessage(TransactionId.testing, Right(activation))
    ResultMessage.parse(m.serialize) shouldBe Success(m)
  }

  behavior of "acknowledgement message"

  it should "serialize a Completion message" in {
    val c = CompletionMessage(
      TransactionId.testing,
      ActivationId.generate(),
      false,
      InvokerInstanceId(0, userMemory = defaultUserMemory))
    val m: AcknowledegmentMessage = c
    m.serialize shouldBe c.toJson.compactPrint
  }

  it should "serialize a Result message" in {
    val r = ResultMessage(TransactionId.testing, Left(ActivationId.generate()))
    val m: AcknowledegmentMessage = r
    m.serialize shouldBe r.toJson.compactPrint
  }

  it should "deserialize a Completion message" in {
    val c = CompletionMessage(
      TransactionId.testing,
      ActivationId.generate(),
      false,
      InvokerInstanceId(0, userMemory = defaultUserMemory))
    val m: AcknowledegmentMessage = c
    AcknowledegmentMessage.parse(m.serialize) shouldBe Success(c)
  }

  it should "deserialize a Result message" in {
    val r = ResultMessage(TransactionId.testing, Left(ActivationId.generate()))
    val m: AcknowledegmentMessage = r
    AcknowledegmentMessage.parse(m.serialize) shouldBe Success(r)
  }
}

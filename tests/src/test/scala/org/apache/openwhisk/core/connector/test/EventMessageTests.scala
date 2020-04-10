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

package org.apache.openwhisk.core.connector.test

import java.time.Instant
import java.util.concurrent.TimeUnit

import org.junit.runner.RunWith
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.junit.JUnitRunner
import spray.json._
import spray.json.DefaultJsonProtocol._
import org.apache.openwhisk.core.connector.Activation
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size._

import scala.concurrent.duration._
import scala.util.Success

/**
 * Unit tests for the EventMessage objects.
 */
@RunWith(classOf[JUnitRunner])
class EventMessageTests extends FlatSpec with Matchers {

  behavior of "Activation"

  val activationId = ActivationId.generate()
  val fullActivation = WhiskActivation(
    namespace = EntityPath("ns"),
    name = EntityName("a"),
    Subject(),
    activationId = activationId,
    start = Instant.now(),
    end = Instant.now(),
    response = ActivationResponse.success(Some(JsObject("res" -> JsNumber(1))), Some(42)),
    annotations = Parameters("limits", ActionLimits(TimeLimit(1.second), MemoryLimit(128.MB), LogLimit(1.MB)).toJson) ++
      Parameters(WhiskActivation.waitTimeAnnotation, 5.toJson) ++
      Parameters(WhiskActivation.initTimeAnnotation, 10.toJson) ++
      Parameters(WhiskActivation.kindAnnotation, "testkind") ++
      Parameters(WhiskActivation.pathAnnotation, "ns2/a") ++
      Parameters(WhiskActivation.causedByAnnotation, "sequence"),
    duration = Some(123))

  it should "transform an activation into an event body" in {
    Activation.from(fullActivation) shouldBe Success(
      Activation(
        "ns2/a",
        activationId.asString,
        0,
        toDuration(123),
        toDuration(5),
        toDuration(10),
        "testkind",
        false,
        128,
        Some("sequence"),
        Some(42)))
  }

  it should "fail transformation if needed annotations are missing" in {
    Activation.from(fullActivation.copy(annotations = Parameters())) shouldBe 'failure
    Activation.from(fullActivation.copy(annotations = fullActivation.annotations - WhiskActivation.kindAnnotation)) shouldBe 'failure
    Activation.from(fullActivation.copy(annotations = fullActivation.annotations - WhiskActivation.pathAnnotation)) shouldBe 'failure
  }

  it should "provide sensible defaults for optional annotations" in {
    val a =
      fullActivation
        .copy(
          duration = None,
          annotations = Parameters(WhiskActivation.kindAnnotation, "testkind") ++ Parameters(
            WhiskActivation.pathAnnotation,
            "ns2/a"))

    Activation.from(a) shouldBe Success(
      Activation(
        "ns2/a",
        activationId.asString,
        0,
        toDuration(0),
        toDuration(0),
        toDuration(0),
        "testkind",
        false,
        0,
        None,
        Some(42)))
  }

  it should "Transform a activation with status code" in {
    val resultWithError =
      """
        |{
        | "statusCode" : 404,
        | "body": "Requested resource not found"
        |}
        |""".stripMargin.parseJson
    val a =
      fullActivation
        .copy(response = ActivationResponse.applicationError(resultWithError, Some(42)))
    Activation.from(a).map(act => act.userDefinedStatusCode) shouldBe Success(Some(404))
  }

  it should "Transform a activation with error status code" in {
    val resultWithError =
      """
        |{
        | "error": {
        |   "statusCode" : "404",
        |   "body": "Requested resource not found"
        | }
        |}
        |""".stripMargin.parseJson
    Activation.userDefinedStatusCode(Some(resultWithError)) shouldBe Some(404)
  }

  it should "Transform a activation with error status code with invalid error code" in {
    val resultWithInvalidError =
      """
        |{
        |   "statusCode" : "i404",
        |   "body": "Requested resource not found"
        |}
        |""".stripMargin.parseJson
    Activation.userDefinedStatusCode(Some(resultWithInvalidError)) shouldBe Some(400)
  }

  def toDuration(milliseconds: Long) = new FiniteDuration(milliseconds, TimeUnit.MILLISECONDS)
}

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

import common.StreamLogging
import io.restassured.RestAssured
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.entity.{ExecManifest, LogLimit, MemoryLimit, TimeLimit}
import org.junit.runner.RunWith
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.junit.JUnitRunner
import spray.json.DefaultJsonProtocol._
import spray.json._
import system.rest.RestUtil

/**
 * Integration tests for Controller routes
 */
@RunWith(classOf[JUnitRunner])
class ControllerApiTests extends FlatSpec with RestUtil with Matchers with StreamLogging {

  it should "ensure controller returns info" in {
    val response = RestAssured.given.config(sslconfig).get(getServiceURL)
    val config = new WhiskConfig(
      Map(
        WhiskConfig.actionInvokePerMinuteLimit -> null,
        WhiskConfig.triggerFirePerMinuteLimit -> null,
        WhiskConfig.actionInvokeConcurrentLimit -> null,
        WhiskConfig.runtimesManifest -> null,
        WhiskConfig.actionSequenceMaxLimit -> null))
    ExecManifest.initialize(config) should be a 'success

    val expectedJson = JsObject(
      "support" -> JsObject(
        "github" -> "https://github.com/apache/openwhisk/issues".toJson,
        "slack" -> "http://slack.openwhisk.org".toJson),
      "description" -> "OpenWhisk".toJson,
      "api_paths" -> JsArray("/api/v1".toJson),
      "runtimes" -> ExecManifest.runtimesManifest.toJson,
      "limits" -> JsObject(
        "actions_per_minute" -> config.actionInvokePerMinuteLimit.toInt.toJson,
        "triggers_per_minute" -> config.triggerFirePerMinuteLimit.toInt.toJson,
        "concurrent_actions" -> config.actionInvokeConcurrentLimit.toInt.toJson,
        "sequence_length" -> config.actionSequenceLimit.toInt.toJson,
        "min_action_duration" -> TimeLimit.config.min.toMillis.toJson,
        "max_action_duration" -> TimeLimit.config.max.toMillis.toJson,
        "min_action_memory" -> MemoryLimit.config.min.toBytes.toJson,
        "max_action_memory" -> MemoryLimit.config.max.toBytes.toJson,
        "min_action_logs" -> LogLimit.config.min.toBytes.toJson,
        "max_action_logs" -> LogLimit.config.max.toBytes.toJson))
    response.statusCode should be(200)
    response.body.asString.parseJson shouldBe (expectedJson)
  }

  behavior of "Controller"

  it should "return list of invokers" in {
    val response = RestAssured.given.config(sslconfig).get(s"$getServiceURL/invokers")

    response.statusCode shouldBe 200
    response.body.asString shouldBe "{\"invoker0/0\":\"up\",\"invoker1/1\":\"up\"}"
  }

  it should "return healthy invokers status" in {
    val response = RestAssured.given.config(sslconfig).get(s"$getServiceURL/invokers/healthy/count")

    response.statusCode shouldBe 200
    response.body.asString shouldBe "2"
  }

  it should "return healthy invokers" in {
    val response = RestAssured.given.config(sslconfig).get(s"$getServiceURL/invokers/ready")

    response.statusCode shouldBe 200
    response.body.asString shouldBe "{\"healthy\":\"2/2\"}"
  }

}

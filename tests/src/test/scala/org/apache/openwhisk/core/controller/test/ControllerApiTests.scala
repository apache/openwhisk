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

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import io.restassured.RestAssured
import common.StreamLogging
import spray.json._
import spray.json.DefaultJsonProtocol._
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.entity._
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

    response.statusCode should be(200)
    val jObj = response.body.asString.parseJson.convertTo[JsObject]
    jObj.fields("support") shouldBe JsObject(
      "github" -> "https://github.com/apache/openwhisk/issues".toJson,
      "slack" -> "http://slack.openwhisk.org".toJson)
    jObj.fields("description") shouldBe "OpenWhisk".toJson
    jObj.fields("api_paths") shouldBe JsArray("/api/v1".toJson)
    jObj.fields("runtimes") shouldBe ExecManifest.runtimesManifest.toJson
    val limits = jObj.fields("limits").convertTo[JsObject]
    limits.fields("actions_per_minute") shouldBe config.actionInvokePerMinuteLimit.toInt.toJson
    limits.fields("triggers_per_minute") shouldBe config.triggerFirePerMinuteLimit.toInt.toJson
    limits.fields("concurrent_actions") shouldBe config.actionInvokeConcurrentLimit.toInt.toJson
    limits.fields("sequence_length") shouldBe config.actionSequenceLimit.toInt.toJson
    limits.fields("min_action_duration") shouldBe TimeLimit.config.min.toMillis.toJson
    limits.fields("max_action_duration") shouldBe TimeLimit.config.max.toMillis.toJson
    limits.fields("min_action_memory") shouldBe MemoryLimit.config.min.toBytes.toJson
    limits.fields("max_action_memory") shouldBe MemoryLimit.config.max.toBytes.toJson
    limits.fields("min_action_logs") shouldBe LogLimit.config.max.toBytes.toJson
    limits.fields("max_action_logs") shouldBe LogLimit.config.max.toBytes.toJson
    limits.fields("cpu_limit_enabled") shouldBe CPULimit.config.controlEnabled.toJson
    // CPU was float number
    limits.fields("min_action_cpu").toString() should startWith("0.1")
    limits.fields("max_action_cpu").toString() should startWith("1.0")
  }
}

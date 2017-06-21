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

package system.rest

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import com.jayway.restassured.RestAssured

import common.WhiskProperties

import spray.json._

@RunWith(classOf[JUnitRunner])
class ControllerInfo extends FlatSpec with Matchers with RestUtil {

    it should "get info route from controller" in {
        val response = RestAssured.given.config(sslconfig).get(getServiceURL)

        response.statusCode shouldBe 200
        response.body.asString.contains("\"support\":") shouldBe true
        response.body.asString.contains("\"description\":") shouldBe true
        response.body.asString.contains("\"runtimes\":") shouldBe true
        response.body.asString.contains("\"limits\":") shouldBe true

        val limits = response.body.asString.parseJson.asJsObject.fields("limits").asJsObject
        val expectedLimits = JsObject(
            "action_blocking_timeout" -> JsNumber(WhiskProperties.getActionInvokeBlockingTimeout),
            "concurrent_actions" -> JsNumber(WhiskProperties.getActionInvokeConcurent),
            //"concurrent_actions_in_system" -> JsNumber(WhiskProperties.getActionInvokeConcurentInSystem),
            "actions_per_minute" -> JsNumber(WhiskProperties.getMaxActionInvokesPerMinute),
            //"action_sequence_max" -> JsNumber(WhiskProperties.getActionSequenceMaxLength),
            "activation_poll_record_limit" -> JsNumber(WhiskProperties.getActivationPollMaxRecords),
            "triggers_per_minute" -> JsNumber(WhiskProperties.getTriggerFiresPerMinute))

        limits shouldBe expectedLimits
    }
}

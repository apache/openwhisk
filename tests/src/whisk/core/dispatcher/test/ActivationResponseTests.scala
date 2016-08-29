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

package whisk.core.dispatcher.test

import scala.Vector

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import spray.json.pimpAny
import spray.json.pimpString
import whisk.common.Logging
import whisk.core.entity.ActivationResponse._

@RunWith(classOf[JUnitRunner])
class ActivationResponseTests extends FlatSpec with Matchers {

    behavior of "ActivationResponse"

    val logger = new Logging {}

    it should "interpret failed init that does not response" in {
        val ar = processInitResponseContent(None, logger)
        ar.statusCode shouldBe ContainerError
        ar.result.get.asJsObject.fields(ERROR_FIELD) shouldBe abnormalInitialization
    }

    it should "interpret failed init that responds with null string" in {
        val response = Some(500, null)
        val ar = processInitResponseContent(response, logger)
        ar.statusCode shouldBe ContainerError
        ar.result.get.asJsObject.fields(ERROR_FIELD) shouldBe invalidInitResponse(response.get._2)
        ar.result.get.toString should not include regex("null")
    }

    it should "interpret failed init that responds with empty string" in {
        val response = Some(500, "")
        val ar = processInitResponseContent(response, logger)
        ar.statusCode shouldBe ContainerError
        ar.result.get.asJsObject.fields(ERROR_FIELD) shouldBe invalidInitResponse(response.get._2)
        ar.result.get.asJsObject.fields(ERROR_FIELD).toString.endsWith(".\"") shouldBe true
    }

    it should "interpret failed init that responds with non-empty string" in {
        val response = Some(500, "true")
        val ar = processInitResponseContent(response, logger)
        ar.statusCode shouldBe ContainerError
        ar.result.get.asJsObject.fields(ERROR_FIELD) shouldBe invalidInitResponse(response.get._2)
        ar.result.get.toString should include(response.get._2)
    }

    it should "interpret failed init that responds with JSON string not object" in {
        val response = Some(500, Vector(1).toJson.compactPrint)
        val ar = processInitResponseContent(response, logger)
        ar.statusCode shouldBe ContainerError
        ar.result.get.asJsObject.fields(ERROR_FIELD) shouldBe invalidInitResponse(response.get._2)
        ar.result.get.toString should include(response.get._2)
    }

    it should "interpret failed init that responds with JSON object containing error" in {
        val response = Some(500, Map(ERROR_FIELD -> "foobar").toJson.compactPrint)
        val ar = processInitResponseContent(response, logger)
        ar.statusCode shouldBe ContainerError
        ar.result.get shouldBe response.get._2.parseJson
    }

    it should "interpret failed init that responds with JSON object" in {
        val response = Some(500, Map("foobar" -> "baz").toJson.compactPrint)
        val ar = processInitResponseContent(response, logger)
        ar.statusCode shouldBe ContainerError
        ar.result.get.asJsObject.fields(ERROR_FIELD) shouldBe invalidInitResponse(response.get._2)
        ar.result.get.toString should include("baz")
    }

    it should "not interpret successful init" in {
        val response = Some(200, "")
        an[IllegalArgumentException] should be thrownBy {
            processInitResponseContent(response, logger)
        }
    }

    it should "interpret failed run that does not response" in {
        val ar = processRunResponseContent(None, logger)
        ar.statusCode shouldBe ContainerError
        ar.result.get.asJsObject.fields(ERROR_FIELD) shouldBe abnormalRun
    }

    it should "interpret failed run that responds with null string" in {
        val response = Some(500, null)
        val ar = processRunResponseContent(response, logger)
        ar.statusCode shouldBe ContainerError
        ar.result.get.asJsObject.fields(ERROR_FIELD) shouldBe invalidRunResponse(response.get._2)
        ar.result.get.toString should not include regex("null")
    }

    it should "interpret failed run that responds with empty string" in {
        val response = Some(500, "")
        val ar = processRunResponseContent(response, logger)
        ar.statusCode shouldBe ContainerError
        ar.result.get.asJsObject.fields(ERROR_FIELD) shouldBe invalidRunResponse(response.get._2)
        ar.result.get.asJsObject.fields(ERROR_FIELD).toString.endsWith(".\"") shouldBe true
    }

    it should "interpret failed run that responds with non-empty string" in {
        val response = Some(500, "true")
        val ar = processRunResponseContent(response, logger)
        ar.statusCode shouldBe ContainerError
        ar.result.get.asJsObject.fields(ERROR_FIELD) shouldBe invalidRunResponse(response.get._2)
        ar.result.get.toString should include(response.get._2)
    }

    it should "interpret failed run that responds with JSON string not object" in {
        val response = Some(500, Vector(1).toJson.compactPrint)
        val ar = processRunResponseContent(response, logger)
        ar.statusCode shouldBe ContainerError
        ar.result.get.asJsObject.fields(ERROR_FIELD) shouldBe invalidRunResponse(response.get._2)
        ar.result.get.toString should include(response.get._2)
    }

    it should "interpret failed run that responds with JSON object containing error" in {
        val response = Some(500, Map(ERROR_FIELD -> "foobar").toJson.compactPrint)
        val ar = processRunResponseContent(response, logger)
        ar.statusCode shouldBe ContainerError
        ar.result.get shouldBe response.get._2.parseJson
    }

    it should "interpret failed run that responds with JSON object" in {
        val response = Some(500, Map("foobar" -> "baz").toJson.compactPrint)
        val ar = processRunResponseContent(response, logger)
        ar.statusCode shouldBe ContainerError
        ar.result.get.asJsObject.fields(ERROR_FIELD) shouldBe invalidRunResponse(response.get._2)
        ar.result.get.toString should include("baz")
    }

    it should "interpret successful run that responds with JSON object containing error" in {
        val response = Some(200, Map(ERROR_FIELD -> "foobar").toJson.compactPrint)
        val ar = processRunResponseContent(response, logger)
        ar.statusCode shouldBe ApplicationError
        ar.result.get shouldBe response.get._2.parseJson
    }

    it should "interpret successful run that responds with JSON object" in {
        val response = Some(200, Map("foobar" -> "baz").toJson.compactPrint)
        val ar = processRunResponseContent(response, logger)
        ar.statusCode shouldBe Success
        ar.result.get shouldBe response.get._2.parseJson
    }

}

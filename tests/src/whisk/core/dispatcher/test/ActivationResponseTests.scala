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
import whisk.common.PrintStreamLogging
import whisk.core.entity.ActivationResponse._
import whisk.core.entity.size.SizeInt
import whisk.http.Messages._

@RunWith(classOf[JUnitRunner])
class ActivationResponseTests extends FlatSpec with Matchers {

    behavior of "ActivationResponse"

    val logger = new PrintStreamLogging()

    it should "interpret truncated response" in {
        val max = 5.B
        Seq("abcdef", """{"msg":"abcedf"}""", """["a","b","c","d","e"]""").foreach { m =>
            {
                val response = ContainerResponse(okStatus = false, m.take(max.toBytes.toInt - 1), Some(m.length.B, max))
                val init = processInitResponseContent(Right(response), logger)
                init.statusCode shouldBe ContainerError
                init.result.get.asJsObject.fields(ERROR_FIELD) shouldBe truncatedResponse(response.entity, m.length.B, max).toJson
            }
            {
                val response = ContainerResponse(okStatus = true, m.take(max.toBytes.toInt - 1), Some(m.length.B, max))
                val run = processRunResponseContent(Right(response), logger)
                run.statusCode shouldBe ContainerError
                run.result.get.asJsObject.fields(ERROR_FIELD) shouldBe truncatedResponse(response.entity, m.length.B, max).toJson
            }
        }
    }

    it should "interpret failed init that does not response" in {
        Seq(NoHost(), ConnectionError(new Throwable()), NoResponseReceived(), Timeout())
            .map(Left(_)).foreach { e =>
                val ar = processInitResponseContent(e, logger)
                ar.statusCode shouldBe ContainerError
                ar.result.get.asJsObject.fields(ERROR_FIELD) shouldBe abnormalInitialization.toJson
            }
    }

    it should "interpret failed init that responds with null string" in {
        val response = ContainerResponse(okStatus = false, null)
        val ar = processInitResponseContent(Right(response), logger)
        ar.statusCode shouldBe ContainerError
        ar.result.get.asJsObject.fields(ERROR_FIELD) shouldBe invalidInitResponse(response.entity).toJson
        ar.result.get.toString should not include regex("null")
    }

    it should "interpret failed init that responds with empty string" in {
        val response = ContainerResponse(okStatus = false, "")
        val ar = processInitResponseContent(Right(response), logger)
        ar.statusCode shouldBe ContainerError
        ar.result.get.asJsObject.fields(ERROR_FIELD) shouldBe invalidInitResponse(response.entity).toJson
        ar.result.get.asJsObject.fields(ERROR_FIELD).toString.endsWith(".\"") shouldBe true
    }

    it should "interpret failed init that responds with non-empty string" in {
        val response = ContainerResponse(okStatus = false, "string")
        val ar = processInitResponseContent(Right(response), logger)
        ar.statusCode shouldBe ContainerError
        ar.result.get.asJsObject.fields(ERROR_FIELD) shouldBe invalidInitResponse(response.entity).toJson
        ar.result.get.toString should include(response.entity)
    }

    it should "interpret failed init that responds with JSON string not object" in {
        val response = ContainerResponse(okStatus = false, Vector(1).toJson.compactPrint)
        val ar = processInitResponseContent(Right(response), logger)
        ar.statusCode shouldBe ContainerError
        ar.result.get.asJsObject.fields(ERROR_FIELD) shouldBe invalidInitResponse(response.entity).toJson
        ar.result.get.toString should include(response.entity)
    }

    it should "interpret failed init that responds with JSON object containing error" in {
        val response = ContainerResponse(okStatus = false, Map(ERROR_FIELD -> "foobar").toJson.compactPrint)
        val ar = processInitResponseContent(Right(response), logger)
        ar.statusCode shouldBe ContainerError
        ar.result.get shouldBe response.entity.parseJson
    }

    it should "interpret failed init that responds with JSON object" in {
        val response = ContainerResponse(okStatus = false, Map("foobar" -> "baz").toJson.compactPrint)
        val ar = processInitResponseContent(Right(response), logger)
        ar.statusCode shouldBe ContainerError
        ar.result.get.asJsObject.fields(ERROR_FIELD) shouldBe invalidInitResponse(response.entity).toJson
        ar.result.get.toString should include("baz")
    }

    it should "not interpret successful init" in {
        val response = ContainerResponse(okStatus = true, "")
        an[IllegalArgumentException] should be thrownBy {
            processInitResponseContent(Right(response), logger)
        }
    }

    it should "interpret failed run that does not response" in {
        Seq(NoHost(), ConnectionError(new Throwable()), NoResponseReceived(), Timeout())
            .map(Left(_)).foreach { e =>
                val ar = processRunResponseContent(e, logger)
                ar.statusCode shouldBe ContainerError
                ar.result.get.asJsObject.fields(ERROR_FIELD) shouldBe abnormalRun.toJson
            }
    }

    it should "interpret failed run that responds with null string" in {
        val response = ContainerResponse(okStatus = false, null)
        val ar = processRunResponseContent(Right(response), logger)
        ar.statusCode shouldBe ContainerError
        ar.result.get.asJsObject.fields(ERROR_FIELD) shouldBe invalidRunResponse(response.entity).toJson
        ar.result.get.toString should not include regex("null")
    }

    it should "interpret failed run that responds with empty string" in {
        val response = ContainerResponse(okStatus = false, "")
        val ar = processRunResponseContent(Right(response), logger)
        ar.statusCode shouldBe ContainerError
        ar.result.get.asJsObject.fields(ERROR_FIELD) shouldBe invalidRunResponse(response.entity).toJson
        ar.result.get.asJsObject.fields(ERROR_FIELD).toString.endsWith(".\"") shouldBe true
    }

    it should "interpret failed run that responds with non-empty string" in {
        val response = ContainerResponse(okStatus = false, "string")
        val ar = processRunResponseContent(Right(response), logger)
        ar.statusCode shouldBe ContainerError
        ar.result.get.asJsObject.fields(ERROR_FIELD) shouldBe invalidRunResponse(response.entity).toJson
        ar.result.get.toString should include(response.entity)
    }

    it should "interpret failed run that responds with JSON string not object" in {
        val response = ContainerResponse(okStatus = false, Vector(1).toJson.compactPrint)
        val ar = processRunResponseContent(Right(response), logger)
        ar.statusCode shouldBe ContainerError
        ar.result.get.asJsObject.fields(ERROR_FIELD) shouldBe invalidRunResponse(response.entity).toJson
        ar.result.get.toString should include(response.entity)
    }

    it should "interpret failed run that responds with JSON object containing error" in {
        val response = ContainerResponse(okStatus = false, Map(ERROR_FIELD -> "foobar").toJson.compactPrint)
        val ar = processRunResponseContent(Right(response), logger)
        ar.statusCode shouldBe ContainerError
        ar.result.get shouldBe response.entity.parseJson
    }

    it should "interpret failed run that responds with JSON object" in {
        val response = ContainerResponse(okStatus = false, Map("foobar" -> "baz").toJson.compactPrint)
        val ar = processRunResponseContent(Right(response), logger)
        ar.statusCode shouldBe ContainerError
        ar.result.get.asJsObject.fields(ERROR_FIELD) shouldBe invalidRunResponse(response.entity).toJson
        ar.result.get.toString should include("baz")
    }

    it should "interpret successful run that responds with JSON object containing error" in {
        val response = ContainerResponse(okStatus = true, Map(ERROR_FIELD -> "foobar").toJson.compactPrint)
        val ar = processRunResponseContent(Right(response), logger)
        ar.statusCode shouldBe ApplicationError
        ar.result.get shouldBe response.entity.parseJson
    }

    it should "interpret successful run that responds with JSON object" in {
        val response = ContainerResponse(okStatus = true, Map("foobar" -> "baz").toJson.compactPrint)
        val ar = processRunResponseContent(Right(response), logger)
        ar.statusCode shouldBe Success
        ar.result.get shouldBe response.entity.parseJson
    }

}

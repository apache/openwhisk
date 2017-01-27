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

package whisk.core.container.test

import java.time.Instant
import java.nio.charset.StandardCharsets

import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfter
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import org.apache.http.localserver.LocalServerTestBase
import org.apache.http.protocol.HttpRequestHandler
import org.apache.http.HttpResponse
import org.apache.http.HttpRequest
import org.apache.http.protocol.HttpContext
import org.apache.http.entity.StringEntity

import spray.json.JsObject

import whisk.core.container.HttpUtils
import whisk.core.entity.size._
import whisk.core.entity.ActivationResponse._

/**
 * Unit tests for HttpUtils which communicate with containers.
 */
@RunWith(classOf[JUnitRunner])
class ContainerConnectionTests
    extends FlatSpec
    with Matchers
    with BeforeAndAfter
    with BeforeAndAfterAll {

    var testHang: FiniteDuration = 0.second
    var testStatusOK: Boolean = true
    var testResponse: String = null

    val mockServer = new LocalServerTestBase {
        override def setUp() = {
            super.setUp()
            this.serverBootstrap.registerHandler("/init", new HttpRequestHandler() {
                override def handle(request: HttpRequest, response: HttpResponse, context: HttpContext) = {
                    if (testHang.length > 0) {
                        Thread.sleep(testHang.toMillis)
                    }
                    response.setStatusCode(if (testStatusOK) 200 else 500);
                    if (testResponse != null) {
                        response.setEntity(new StringEntity(testResponse, StandardCharsets.UTF_8))
                    }
                }
            })
        }
    }

    mockServer.setUp()
    val httpHost = mockServer.start()
    val hostWithPort = s"${httpHost.getHostName}:${httpHost.getPort}"

    before {
        testHang = 0.second
        testStatusOK = true
        testResponse = null
    }

    override def afterAll = {
        mockServer.shutDown()
    }

    behavior of "Container HTTP Utils"

    it should "not wait longer than set timeout" in {
        val timeout = 5.seconds
        val connection = new HttpUtils(hostWithPort, timeout, 1.B)
        testHang = timeout * 2
        val start = Instant.now()
        val result = connection.post("/init", JsObject())
        val end = Instant.now()
        val waited = end.toEpochMilli - start.toEpochMilli
        result.isLeft shouldBe true
        waited should be > timeout.toMillis
        waited should be < (timeout * 2).toMillis
    }

    it should "not truncate responses within limit" in {
        val timeout = 1.minute.toMillis
        val connection = new HttpUtils(hostWithPort, timeout.millis, 50.B)
        Seq(true, false).foreach { code =>
            Seq(null, "", "abc", """{"a":"B"}""", """["a", "b"]""").foreach { r =>
                testStatusOK = code
                testResponse = r
                val result = connection.post("/init", JsObject())
                result shouldBe Right {
                    ContainerResponse(okStatus = testStatusOK, if (r != null) r else "", None)
                }
            }
        }
    }

    it should "truncate responses that exceed limit" in {
        val timeout = 1.minute.toMillis
        val limit = 1.B
        val excess = limit + 1.B
        val connection = new HttpUtils(hostWithPort, timeout.millis, limit)
        Seq(true, false).foreach { code =>
            Seq("abc", """{"a":"B"}""", """["a", "b"]""").foreach { r =>
                testStatusOK = code
                testResponse = r
                val result = connection.post("/init", JsObject())
                result shouldBe Right {
                    ContainerResponse(okStatus = testStatusOK, r.take(limit.toBytes.toInt), Some((r.length.B, limit)))
                }
            }
        }
    }
}

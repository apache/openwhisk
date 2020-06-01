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

package org.apache.openwhisk.core.containerpool.docker.test

import common.StreamLogging
import common.WskActorSystem
import java.nio.charset.StandardCharsets
import java.time.Instant
import org.apache.http.HttpRequest
import org.apache.http.HttpResponse
import org.apache.http.entity.StringEntity
import org.apache.http.localserver.LocalServerTestBase
import org.apache.http.protocol.HttpContext
import org.apache.http.protocol.HttpRequestHandler
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfter
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import scala.concurrent.Await
import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import spray.json.JsObject
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.containerpool.AkkaContainerClient
import org.apache.openwhisk.core.containerpool.ContainerHealthError
import org.apache.openwhisk.core.entity.ActivationResponse._
import org.apache.openwhisk.core.entity.size._

/**
 * Unit tests for AkkaContainerClientTests which communicate with containers.
 */
@RunWith(classOf[JUnitRunner])
class AkkaContainerClientTests
    extends FlatSpec
    with Matchers
    with BeforeAndAfter
    with BeforeAndAfterAll
    with StreamLogging
    with WskActorSystem {

  implicit val transid = TransactionId.testing
  implicit val ec = actorSystem.dispatcher

  var testHang: FiniteDuration = 0.second
  var testStatusCode: Int = 200
  var testResponse: String = null
  var testConnectionFailCount: Int = 0

  val mockServer = new LocalServerTestBase {
    var failcount = 0
    override def setUp() = {
      super.setUp()
      this.serverBootstrap
        .registerHandler(
          "/init",
          new HttpRequestHandler() {
            override def handle(request: HttpRequest, response: HttpResponse, context: HttpContext) = {
              if (testHang.length > 0) {
                Thread.sleep(testHang.toMillis)
              }
              if (testConnectionFailCount > 0 && failcount < testConnectionFailCount) {
                failcount += 1
                println("failing in test")
                throw new RuntimeException("failing...")
              }
              response.setStatusCode(testStatusCode);
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
    testStatusCode = 200
    testResponse = null
    testConnectionFailCount = 0
    stream.reset()
  }

  override def afterAll = {
    mockServer.shutDown()
  }

  behavior of "AkkaContainerClient"

  it should "not wait longer than set timeout" in {
    val timeout = 5.seconds
    val connection = new AkkaContainerClient(httpHost.getHostName, httpHost.getPort, timeout, 1.B, 1.B, 100)
    testHang = timeout * 2
    val start = Instant.now()
    val result = Await.result(connection.post("/init", JsObject.empty, retry = true), 10.seconds)

    val end = Instant.now()
    val waited = end.toEpochMilli - start.toEpochMilli
    result shouldBe 'left
    waited should be > timeout.toMillis
    waited should be < (timeout * 2).toMillis
  }

  it should "handle empty entity response" in {
    val timeout = 5.seconds
    val connection = new AkkaContainerClient(httpHost.getHostName, httpHost.getPort, timeout, 1.B, 1.B, 100)
    testStatusCode = 204
    val result = Await.result(connection.post("/init", JsObject.empty, retry = true), 10.seconds)
    result shouldBe Left(NoResponseReceived())
  }

  it should "retry till timeout on StreamTcpException" in {
    val timeout = 5.seconds
    val connection = new AkkaContainerClient("0.0.0.0", 12345, timeout, 1.B, 1.B, 100)
    val start = Instant.now()
    val result = Await.result(connection.post("/init", JsObject.empty, retry = true), 10.seconds)
    val end = Instant.now()
    val waited = end.toEpochMilli - start.toEpochMilli
    result match {
      case Left(Timeout(_: TimeoutException)) => // good
      case _                                  => fail(s"$result was not a Timeout(TimeoutException)")
    }
    waited should be > timeout.toMillis
    waited should be < (timeout * 2).toMillis
  }

  it should "throw ContainerHealthError on HttpHostConnectException if reschedule==true" in {
    val timeout = 5.seconds
    val connection = new AkkaContainerClient("0.0.0.0", 12345, timeout, 1.B, 1.B, 100)
    assertThrows[ContainerHealthError] {
      Await.result(connection.post("/run", JsObject.empty, retry = false, reschedule = true), 10.seconds)
    }
  }

  it should "retry till success within timeout limit" in {
    val timeout = 5.seconds
    val retryInterval = 500.milliseconds
    val connection =
      new AkkaContainerClient(httpHost.getHostName, httpHost.getPort, timeout, 1.B, 1.B, 100, retryInterval)
    val start = Instant.now()
    testConnectionFailCount = 5
    testResponse = ""
    val result = Await.result(connection.post("/init", JsObject.empty, retry = true), 10.seconds)
    val end = Instant.now()
    val waited = end.toEpochMilli - start.toEpochMilli
    result shouldBe Right {
      ContainerResponse(true, "", None)
    }

    waited should be > (testConnectionFailCount * retryInterval).toMillis
    waited should be < timeout.toMillis
  }

  it should "not truncate responses within limit" in {
    val timeout = 1.minute.toMillis
    val connection = new AkkaContainerClient(httpHost.getHostName, httpHost.getPort, timeout.millis, 50.B, 50.B, 100)
    Seq(true, false).foreach { success =>
      Seq(null, "", "abc", """{"a":"B"}""", """["a", "b"]""").foreach { r =>
        testStatusCode = if (success) 200 else 500
        testResponse = r
        val result = Await.result(connection.post("/init", JsObject.empty, retry = true), 10.seconds)
        result shouldBe Right {
          ContainerResponse(okStatus = success, if (r != null) r else "", None)
        }
      }
    }
  }

  it should "truncate responses that exceed limit" in {
    val timeout = 1.minute.toMillis
    val limit = 2.B
    val truncationLimit = 1.B
    val connection =
      new AkkaContainerClient(httpHost.getHostName, httpHost.getPort, timeout.millis, limit, truncationLimit, 100)
    Seq(true, false).foreach { success =>
      Seq("abc", """{"a":"B"}""", """["a", "b"]""").foreach { r =>
        testStatusCode = if (success) 200 else 500
        testResponse = r
        val result = Await.result(connection.post("/init", JsObject.empty, retry = true), 10.seconds)
        result shouldBe Right {
          ContainerResponse(okStatus = success, r.take(truncationLimit.toBytes.toInt), Some((r.length.B, limit)))
        }
      }
    }
  }

  it should "truncate large responses that exceed limit" in {
    val timeout = 1.minute.toMillis
    //use a limit large enough to not fit into a single ByteString as response entity is parsed into multiple ByteStrings
    //seems like this varies, but often is ~64k or ~128k
    val limit = 300.KB
    val truncationLimit = 299.B
    val connection =
      new AkkaContainerClient(httpHost.getHostName, httpHost.getPort, timeout.millis, limit, truncationLimit, 100)
    Seq(true, false).foreach { success =>
      // Generate a response that's 1MB
      val response = "0" * 1024 * 1024
      testStatusCode = if (success) 200 else 500
      testResponse = response
      val result = Await.result(connection.post("/init", JsObject.empty, retry = true), 10.seconds)
      result shouldBe Right {
        ContainerResponse(
          okStatus = success,
          response.take(truncationLimit.toBytes.toInt),
          Some((response.length.B, limit)))
      }

    }
  }
}

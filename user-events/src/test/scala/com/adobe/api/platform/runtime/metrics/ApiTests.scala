/*
Copyright 2018 Adobe. All rights reserved.
This file is licensed to you under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License. You may obtain a copy
of the License at http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under
the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
OF ANY KIND, either express or implied. See the License for the specific language
governing permissions and limitations under the License.
 */

package com.adobe.api.platform.runtime.metrics

import akka.http.scaladsl.model.headers.HttpEncodings._
import akka.http.scaladsl.model.headers.{`Accept-Encoding`, `Content-Encoding`, HttpEncoding, HttpEncodings}
import akka.http.scaladsl.model.{HttpCharsets, HttpEntity, HttpResponse}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.junit.runner.RunWith
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.Matcher
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration.DurationInt

@RunWith(classOf[JUnitRunner])
class ApiTests extends FlatSpec with Matchers with ScalatestRouteTest with EventsTestHelper with ScalaFutures {
  implicit val timeoutConfig = PatienceConfig(1.minute)
  behavior of "EventsApi"

  it should "respond ping request" in {
    val consumer = createConsumer(56754, system.settings.config)
    val api = new PrometheusEventsApi(consumer, createExporter())
    Get("/ping") ~> api.routes ~> check {
      //Due to retries using a random port does not immediately result in failure
      handled shouldBe true
    }
    consumer.shutdown().futureValue
  }

  it should "respond metrics request" in {
    val consumer = createConsumer(56754, system.settings.config)
    val api = new PrometheusEventsApi(consumer, createExporter())
    Get("/metrics") ~> `Accept-Encoding`(gzip) ~> api.routes ~> check {
      contentType.charsetOption shouldBe Some(HttpCharsets.`UTF-8`)
      contentType.mediaType.params("version") shouldBe "0.0.4"
      response should haveContentEncoding(gzip)
    }
    consumer.shutdown().futureValue
  }

  private def haveContentEncoding(encoding: HttpEncoding): Matcher[HttpResponse] =
    be(encoding) compose {
      (_: HttpResponse).header[`Content-Encoding`].map(_.encodings.head).getOrElse(HttpEncodings.identity)
    }

  private def createExporter(): PrometheusExporter = () => HttpEntity(PrometheusExporter.textV4, "foo".getBytes)
}

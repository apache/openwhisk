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

package org.apache.openwhisk.core.monitoring.metrics

import akka.http.scaladsl.model.headers.HttpEncodings._
import akka.http.scaladsl.model.headers.{`Accept-Encoding`, `Content-Encoding`, HttpEncoding, HttpEncodings}
import akka.http.scaladsl.model.{HttpCharsets, HttpEntity, HttpResponse}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import kamon.prometheus.PrometheusReporter
import org.apache.openwhisk.core.monitoring.metrics.OpenWhiskEvents.MetricConfig
import org.junit.runner.RunWith
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.Matcher
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import pureconfig.loadConfigOrThrow
import io.prometheus.client.CollectorRegistry
import pureconfig.generic.auto._

import scala.concurrent.duration.DurationInt

@RunWith(classOf[JUnitRunner])
class ApiTests
    extends FlatSpec
    with Matchers
    with ScalatestRouteTest
    with EventsTestHelper
    with ScalaFutures
    with BeforeAndAfterAll {
  implicit val timeoutConfig = PatienceConfig(1.minute)

  private var api: PrometheusEventsApi = _
  private var consumer: EventConsumer = _

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    CollectorRegistry.defaultRegistry.clear()
    val metricConfig = loadConfigOrThrow[MetricConfig](system.settings.config, "user-events")
    val mericRecorder = PrometheusRecorder(new PrometheusReporter, metricConfig)
    consumer = createConsumer(56754, system.settings.config, mericRecorder)
    api = new PrometheusEventsApi(consumer, createExporter())
  }

  protected override def afterAll(): Unit = {
    consumer.shutdown().futureValue
    super.afterAll()
  }

  behavior of "EventsApi"

  it should "respond ping request" in {
    Get("/ping") ~> api.routes ~> check {
      //Due to retries using a random port does not immediately result in failure
      handled shouldBe true
    }
  }

  it should "respond metrics request" in {
    Get("/metrics") ~> `Accept-Encoding`(gzip) ~> api.routes ~> check {
      contentType.charsetOption shouldBe Some(HttpCharsets.`UTF-8`)
      contentType.mediaType.params("version") shouldBe "0.0.4"
      response should haveContentEncoding(gzip)
    }
  }

  private def haveContentEncoding(encoding: HttpEncoding): Matcher[HttpResponse] =
    be(encoding) compose {
      (_: HttpResponse).header[`Content-Encoding`].map(_.encodings.head).getOrElse(HttpEncodings.identity)
    }

  private def createExporter(): PrometheusExporter = () => HttpEntity(PrometheusExporter.textV4, "foo".getBytes)
}

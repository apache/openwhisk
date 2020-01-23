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

package org.apache.openwhisk.common
import akka.http.scaladsl.coding.Gzip
import akka.http.scaladsl.model.{HttpCharsets, HttpResponse}
import akka.http.scaladsl.model.headers.HttpEncodings.gzip
import akka.http.scaladsl.model.headers.{`Accept-Encoding`, `Content-Encoding`, HttpEncoding, HttpEncodings}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.unmarshalling.Unmarshal
import com.typesafe.config.ConfigFactory
import kamon.Kamon
import org.junit.runner.RunWith
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.Matcher
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class PrometheusTests extends FlatSpec with Matchers with ScalatestRouteTest with BeforeAndAfterAll with ScalaFutures {
  behavior of "Prometheus"

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    //Modify Kamon to have a very small tick interval
    val newConfig = ConfigFactory.parseString("""kamon {
      |  metric {
      |    tick-interval = 50 ms
      |    optimistic-tick-alignment = no
      |  }
      |}""".stripMargin).withFallback(ConfigFactory.load())
    Kamon.reconfigure(newConfig)
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    Kamon.reconfigure(ConfigFactory.load())
  }

  it should "respond to /metrics" in {
    val api = new KamonPrometheus
    Kamon.counter("foo_bar").withoutTags().increment(42)

    //Sleep to ensure that Kamon metrics are pushed to reporters
    Thread.sleep(2.seconds.toMillis)
    Get("/metrics") ~> `Accept-Encoding`(gzip) ~> api.route ~> check {
      // Check that response confirms to what Prometheus scrapper accepts
      contentType.charsetOption shouldBe Some(HttpCharsets.`UTF-8`)
      contentType.mediaType.params("version") shouldBe "0.0.4"
      response should haveContentEncoding(gzip)

      val responseText = Unmarshal(Gzip.decodeMessage(response)).to[String].futureValue
      withClue(responseText) {
        responseText should include("foo_bar")
      }
    }
    api.close()
  }

  it should "not be enabled by default" in {
    Get("/metrics") ~> MetricsRoute() ~> check {
      handled shouldBe false
    }
  }

  private def haveContentEncoding(encoding: HttpEncoding): Matcher[HttpResponse] =
    be(encoding) compose {
      (_: HttpResponse).header[`Content-Encoding`].map(_.encodings.head).getOrElse(HttpEncodings.identity)
    }
}

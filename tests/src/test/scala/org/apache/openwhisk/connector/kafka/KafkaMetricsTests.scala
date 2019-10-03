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

package org.apache.openwhisk.connector.kafka

import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.apache.kafka.common.MetricName
import org.apache.kafka.common.metrics.{KafkaMetric, Measurable, MetricConfig}
import org.apache.kafka.common.utils.Time
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}
import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.collection.JavaConverters._
import scala.concurrent.Future

@RunWith(classOf[JUnitRunner])
class KafkaMetricsTests extends FlatSpec with Matchers with ScalatestRouteTest {
  behavior of "KafkaMetrics"

  it should "render metrics as json" in {
    val metricName = new MetricName(
      "bytes-consumed-total",
      "consumer-fetch-manager-metrics",
      "The total number of bytes consumed",
      Map("client-id" -> "event-consumer").asJava)
    val valueProvider: Measurable = (config: MetricConfig, now: Long) => 42
    val metrics = new KafkaMetric(this, metricName, valueProvider, new MetricConfig(), Time.SYSTEM)

    val route = KafkaMetricRoute(() => Future.successful(Map(metricName -> metrics)))
    Get("/metrics/kafka") ~> route ~> check {
      //Due to retries using a random port does not immediately result in failure
      handled shouldBe true
      responseAs[JsArray].elements.size shouldBe 1
    }
  }
}

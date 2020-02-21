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

import io.prometheus.client.CollectorRegistry
import org.apache.openwhisk.core.connector.{Activation, EventMessage}
import org.apache.openwhisk.core.entity.{ActivationResponse, Subject, UUID}
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterEach
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class PrometheusRecorderTests extends KafkaSpecBase with BeforeAndAfterEach with PrometheusMetricNames {
  behavior of "PrometheusConsumer"
  val initiator = "initiatorTest"
  val namespaceDemo = "demo"
  val namespaceGuest = "guest"
  val actionWithCustomPackage = "apimgmt/createApiOne"
  val actionWithDefaultPackage = "createApi"
  val kind = "nodejs:10"
  val memory = "256"

  it should "push user events to kamon" in {
    createCustomTopic(EventConsumer.userEventTopic)

    val consumer = createConsumer(kafkaPort, system.settings.config)
    publishStringMessageToKafka(
      EventConsumer.userEventTopic,
      newActivationEvent(s"$namespaceDemo/$actionWithCustomPackage", kind, memory).serialize)
    publishStringMessageToKafka(
      EventConsumer.userEventTopic,
      newActivationEvent(s"$namespaceDemo/$actionWithDefaultPackage", kind, memory).serialize)

    publishStringMessageToKafka(
      EventConsumer.userEventTopic,
      newActivationEvent(s"$namespaceGuest/$actionWithDefaultPackage", kind, memory).serialize)

    // Custom package
    sleep(sleepAfterProduce, "sleeping post produce")
    consumer.shutdown().futureValue
    counterTotal(activationMetric, namespaceDemo, actionWithCustomPackage) shouldBe 1
    counter(coldStartMetric, namespaceDemo, actionWithCustomPackage) shouldBe 1
    counterStatus(statusMetric, namespaceDemo, actionWithCustomPackage, ActivationResponse.statusDeveloperError) shouldBe 1

    histogramCount(waitTimeMetric, namespaceDemo, actionWithCustomPackage) shouldBe 1
    histogramSum(waitTimeMetric, namespaceDemo, actionWithCustomPackage) shouldBe (0.03 +- 0.001)

    histogramCount(initTimeMetric, namespaceDemo, actionWithCustomPackage) shouldBe 1
    histogramSum(initTimeMetric, namespaceDemo, actionWithCustomPackage) shouldBe (433.433 +- 0.01)

    histogramCount(durationMetric, namespaceDemo, actionWithCustomPackage) shouldBe 1
    histogramSum(durationMetric, namespaceDemo, actionWithCustomPackage) shouldBe (1.254 +- 0.01)

    gauge(memoryMetric, namespaceDemo, actionWithCustomPackage).intValue shouldBe 256

    // Default package
    counterTotal(activationMetric, namespaceDemo, actionWithDefaultPackage) shouldBe 1

    // Blacklisted namespace should not be tracked
    counterTotal(activationMetric, namespaceGuest, actionWithDefaultPackage) shouldBe 0

    // Blacklisted should be counted in "openwhisk_namespace_activations_total" metric
    namespaceCounterTotal(namespaceMetric, namespaceGuest) shouldBe 1
  }

  private def newActivationEvent(actionPath: String, kind: String, memory: String) =
    EventMessage(
      "test",
      Activation(actionPath, 2, 1254.millis, 30.millis, 433433.millis, kind, false, memory.toInt, None),
      Subject("testuser"),
      initiator,
      UUID("test"),
      Activation.typeName)

  private def gauge(metricName: String, namespace: String, action: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(
      metricName,
      Array("namespace", "initiator", "action"),
      Array(namespace, initiator, action))

  private def counter(metricName: String, namespace: String, action: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(
      metricName,
      Array("namespace", "initiator", "action"),
      Array(namespace, initiator, action))

  private def counterTotal(metricName: String, namespace: String, action: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(
      metricName,
      Array("namespace", "initiator", "action", "kind", "memory"),
      Array(namespace, initiator, action, kind, memory))

  private def namespaceCounterTotal(metricName: String, namespace: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(
      metricName,
      Array("namespace", "initiator"),
      Array(namespace, initiator))

  private def counterStatus(metricName: String, namespace: String, action: String, status: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(
      metricName,
      Array("namespace", "initiator", "action", "status"),
      Array(namespace, initiator, action, status))

  private def histogramCount(metricName: String, namespace: String, action: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(
      s"${metricName}_count",
      Array("namespace", "initiator", "action"),
      Array(namespace, initiator, action))

  private def histogramSum(metricName: String, namespace: String, action: String) =
    CollectorRegistry.defaultRegistry
      .getSampleValue(
        s"${metricName}_sum",
        Array("namespace", "initiator", "action"),
        Array(namespace, initiator, action))
      .doubleValue()
}

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
  val namespace = "whisk.system"
  val actionWithCustomPackage = "apimgmt/createApiOne"
  val actionWithDefaultPackage = "createApi"
  val kind = "nodejs:10"
  val memory = "256"

  it should "push user events to kamon" in {
    createCustomTopic(EventConsumer.userEventTopic)

    val consumer = createConsumer(kafkaPort, system.settings.config)
    val initiatorTest = "testNS"
    publishStringMessageToKafka(
      EventConsumer.userEventTopic,
      newActivationEvent(s"$namespace/$actionWithCustomPackage", kind, memory, initiatorTest).serialize)
    publishStringMessageToKafka(
      EventConsumer.userEventTopic,
      newActivationEvent(s"$namespace/$actionWithDefaultPackage", kind, memory, initiatorTest).serialize)

    val initiatorGuest = "guest"
    publishStringMessageToKafka(
      EventConsumer.userEventTopic,
      newActivationEvent(s"$namespace/$actionWithDefaultPackage", kind, memory, initiatorGuest).serialize)


    // Custom package
    sleep(sleepAfterProduce, "sleeping post produce")
    consumer.shutdown().futureValue
    counterTotal(initiatorTest, activationMetric, actionWithCustomPackage) shouldBe 1
    counter(initiatorTest, coldStartMetric, actionWithCustomPackage) shouldBe 1
    counterStatus(initiatorTest, statusMetric, actionWithCustomPackage, ActivationResponse.statusDeveloperError) shouldBe 1

    histogramCount(initiatorTest, waitTimeMetric, actionWithCustomPackage) shouldBe 1
    histogramSum(initiatorTest, waitTimeMetric, actionWithCustomPackage) shouldBe (0.03 +- 0.001)

    histogramCount(initiatorTest, initTimeMetric, actionWithCustomPackage) shouldBe 1
    histogramSum(initiatorTest, initTimeMetric, actionWithCustomPackage) shouldBe (433.433 +- 0.01)

    histogramCount(initiatorTest, durationMetric, actionWithCustomPackage) shouldBe 1
    histogramSum(initiatorTest, durationMetric, actionWithCustomPackage) shouldBe (1.254 +- 0.01)

    gauge(initiatorTest, memoryMetric, actionWithCustomPackage) shouldBe 1

    // Default package
    counterTotal(initiatorTest, activationMetric, actionWithDefaultPackage) shouldBe 1

    // Blacklisted namespace should not be tracked
    counterTotal(initiatorGuest, activationMetric, actionWithDefaultPackage) shouldBe null
  }

  private def newActivationEvent(name: String, kind: String, memory: String, initiator: String) =
    EventMessage(
      "test",
      Activation(name, 2, 1254.millis, 30.millis, 433433.millis, kind, false, memory.toInt, None),
      Subject("testuser"),
      initiator,
      UUID("test"),
      Activation.typeName)

  private def gauge(initiator: String, name: String, action: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(
      s"${name}_count",
      Array("namespace", "initiator", "action"),
      Array(namespace, initiator, action))

  private def counter(initiator: String, name: String, action: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(
      name,
      Array("namespace", "initiator", "action"),
      Array(namespace, initiator, action))

  private def counterTotal(initiator: String, name: String, action: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(
      name,
      Array("namespace", "initiator", "action", "kind", "memory"),
      Array(namespace, initiator, action, kind, memory))

  private def counterStatus(initiator: String, name: String, action: String, status: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(
      name,
      Array("namespace", "initiator", "action", "status"),
      Array(namespace, initiator, action, status))

  private def histogramCount(initiator: String, name: String, action: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(
      s"${name}_count",
      Array("namespace", "initiator", "action"),
      Array(namespace, initiator, action))

  private def histogramSum(initiator: String, name: String, action: String) =
    CollectorRegistry.defaultRegistry
      .getSampleValue(s"${name}_sum", Array("namespace", "initiator", "action"), Array(namespace, initiator, action))
      .doubleValue()
}

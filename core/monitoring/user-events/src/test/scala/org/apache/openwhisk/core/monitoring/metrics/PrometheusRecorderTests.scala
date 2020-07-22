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

import com.typesafe.config.ConfigFactory
import io.prometheus.client.CollectorRegistry
import kamon.prometheus.PrometheusReporter
import org.apache.openwhisk.core.connector.{Activation, EventMessage}
import org.apache.openwhisk.core.entity.{ActivationId, ActivationResponse, Subject, UUID}
import org.apache.openwhisk.core.monitoring.metrics.OpenWhiskEvents.MetricConfig
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterEach
import org.scalatest.junit.JUnitRunner
import pureconfig._
import pureconfig.generic.auto._

import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class PrometheusRecorderTests extends KafkaSpecBase with BeforeAndAfterEach with PrometheusMetricNames {
  behavior of "PrometheusConsumer"
  val namespaceDemo = "demo"
  val namespaceGuest = "guest"
  val actionWithCustomPackage = "apimgmt/createApiOne"
  val actionWithDefaultPackage = "createApi"
  val kind = "nodejs:10"
  val memory = "256"
  createCustomTopic(EventConsumer.userEventTopic)

  it should "push user events to kamon" in {
    CollectorRegistry.defaultRegistry.clear()
    val metricConfig = loadConfigOrThrow[MetricConfig](system.settings.config, "user-events")
    val metricRecorder = PrometheusRecorder(new PrometheusReporter, metricConfig)
    val consumer = createConsumer(kafkaPort, system.settings.config, metricRecorder)
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
    counterTotal(activationMetric, namespaceGuest, actionWithDefaultPackage) shouldBe (null)
  }

  it should "push user event to kamon with prometheus metrics tags relabel" in {
    val httpPort = freePort()
    val globalConfig = system.settings.config
    val config = ConfigFactory.parseString(s"""
            | whisk {
            |  user-events {
            |    port = $httpPort
            |    enable-kamon = false
            |    ignored-namespaces = ["guest"]
            |    rename-tags {
            |      namespace = "ow_namespace"
            |    }
            |    retry {
            |      min-backoff = 3 secs
            |      max-backoff = 30 secs
            |      random-factor = 0.2
            |      max-restarts = 10
            |    }
            |  }
            | }
         """.stripMargin)
    CollectorRegistry.defaultRegistry.clear()
    val metricConfig = loadConfigOrThrow[MetricConfig](config, "whisk.user-events")
    val metricRecorder = PrometheusRecorder(new PrometheusReporter, metricConfig)
    val consumer = createConsumer(kafkaPort, system.settings.config, metricRecorder)

    publishStringMessageToKafka(
      EventConsumer.userEventTopic,
      newActivationEvent(s"$namespaceDemo/$actionWithCustomPackage", kind, memory).serialize)

    sleep(sleepAfterProduce, "sleeping post produce")
    consumer.shutdown().futureValue
    CollectorRegistry.defaultRegistry.getSampleValue(
      activationMetric,
      Array("ow_namespace", "initiator", "action", "kind", "memory"),
      Array(namespaceDemo, namespaceDemo, actionWithCustomPackage, kind, memory)) shouldBe 1
  }
  private def newActivationEvent(actionPath: String, kind: String, memory: String) =
    EventMessage(
      "test",
      Activation(
        actionPath,
        ActivationId.generate().asString,
        2,
        1254.millis,
        30.millis,
        433433.millis,
        kind,
        false,
        memory.toInt,
        None),
      Subject("testuser"),
      actionPath.split("/")(0),
      UUID("test"),
      Activation.typeName)

  private def gauge(metricName: String, namespace: String, action: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(
      metricName,
      Array("namespace", "initiator", "action"),
      Array(namespace, namespace, action))

  private def counter(metricName: String, namespace: String, action: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(
      metricName,
      Array("namespace", "initiator", "action"),
      Array(namespace, namespace, action))

  private def counterTotal(metricName: String, namespace: String, action: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(
      metricName,
      Array("namespace", "initiator", "action", "kind", "memory"),
      Array(namespace, namespace, action, kind, memory))

  private def counterStatus(metricName: String, namespace: String, action: String, status: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(
      metricName,
      Array("namespace", "initiator", "action", "status"),
      Array(namespace, namespace, action, status))

  private def histogramCount(metricName: String, namespace: String, action: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(
      s"${metricName}_count",
      Array("namespace", "initiator", "action"),
      Array(namespace, namespace, action))

  private def histogramSum(metricName: String, namespace: String, action: String) =
    CollectorRegistry.defaultRegistry
      .getSampleValue(
        s"${metricName}_sum",
        Array("namespace", "initiator", "action"),
        Array(namespace, namespace, action))
      .doubleValue()
}

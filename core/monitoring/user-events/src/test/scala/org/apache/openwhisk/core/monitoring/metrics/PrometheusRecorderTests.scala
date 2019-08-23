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
import net.manub.embeddedkafka.EmbeddedKafkaConfig
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterEach
import org.scalatest.junit.JUnitRunner
import org.apache.openwhisk.core.connector.{Activation, EventMessage}
import org.apache.openwhisk.core.entity.{Subject, UUID}

import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class PrometheusRecorderTests extends KafkaSpecBase with BeforeAndAfterEach with PrometheusMetricNames {
  val sleepAfterProduce: FiniteDuration = 4.seconds

  behavior of "PrometheusConsumer"
  val namespace = "whisk.system"
  val initiator = "testNS"
  val action = "apimgmt/createApi"
  val kind = "nodejs:10"
  val memory = "256"

  it should "push user events to kamon" in {
    val kconfig = EmbeddedKafkaConfig(kafkaPort = 0, zooKeeperPort = 0)
    withRunningKafkaOnFoundPort(kconfig) { implicit actualConfig =>
      createCustomTopic(EventConsumer.userEventTopic)

      val consumer = createConsumer(actualConfig.kafkaPort, system.settings.config)
      publishStringMessageToKafka(
        EventConsumer.userEventTopic,
        newActivationEvent(s"$namespace/$action", kind, memory, initiator).serialize)

      sleep(sleepAfterProduce, "sleeping post produce")
      consumer.shutdown().futureValue
      counterTotal(activationMetric) shouldBe 1
      counter(coldStartMetric) shouldBe 1
      counterStatus(statusMetric, Activation.statusDeveloperError) shouldBe 1

      histogramCount(waitTimeMetric) shouldBe 1
      histogramSum(waitTimeMetric) shouldBe (0.03 +- 0.001)

      histogramCount(initTimeMetric) shouldBe 1
      histogramSum(initTimeMetric) shouldBe (433.433 +- 0.01)

      histogramCount(durationMetric) shouldBe 1
      histogramSum(durationMetric) shouldBe (1.254 +- 0.01)

      gauge(memoryMetric) shouldBe 1
    }
  }

  private def newActivationEvent(name: String, kind: String, memory: String, initiator: String) =
    EventMessage(
      "test",
      Activation(name, 2, 1254.millis, 30.millis, 433433.millis, kind, false, memory.toInt, None),
      Subject("testuser"),
      initiator,
      UUID("test"),
      Activation.typeName)

  private def gauge(name: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(
      s"${name}_count",
      Array("namespace", "initiator", "action"),
      Array(namespace, initiator, action))

  private def counter(name: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(
      name,
      Array("namespace", "initiator", "action"),
      Array(namespace, initiator, action))

  private def counterTotal(name: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(
      name,
      Array("namespace", "initiator", "action", "kind", "memory"),
      Array(namespace, initiator, action, kind, memory))

  private def counterStatus(name: String, status: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(
      name,
      Array("namespace", "initiator", "action", "status"),
      Array(namespace, initiator, action, status))

  private def histogramCount(name: String) =
    CollectorRegistry.defaultRegistry.getSampleValue(
      s"${name}_count",
      Array("namespace", "initiator", "action"),
      Array(namespace, initiator, action))

  private def histogramSum(name: String) =
    CollectorRegistry.defaultRegistry
      .getSampleValue(s"${name}_sum", Array("namespace", "initiator", "action"), Array(namespace, initiator, action))
      .doubleValue()
}

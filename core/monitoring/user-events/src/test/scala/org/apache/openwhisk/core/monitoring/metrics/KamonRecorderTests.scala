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

import java.time.Duration

import com.typesafe.config.{Config, ConfigFactory}
import kamon.metric.{PeriodSnapshot, PeriodSnapshotAccumulator}
import kamon.util.Registration
import kamon.{Kamon, MetricReporter}
import org.apache.openwhisk.core.connector.{Activation, EventMessage}
import org.apache.openwhisk.core.entity.{ActivationResponse, Subject, UUID}
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterEach
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class KamonRecorderTests extends KafkaSpecBase with BeforeAndAfterEach with KamonMetricNames {
  var reporterReg: Registration = _

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    TestReporter.reset()
    val newConfig = ConfigFactory.parseString("""kamon {
        |  metric {
        |    tick-interval = 50 ms
        |    optimistic-tick-alignment = no
        |  }
        |}""".stripMargin).withFallback(ConfigFactory.load())
    Kamon.reconfigure(newConfig)
    reporterReg = Kamon.addReporter(TestReporter)
  }

  override protected def afterEach(): Unit = {
    reporterReg.cancel()
    Kamon.reconfigure(ConfigFactory.load())
    super.afterEach()
  }

  behavior of "KamonConsumer"

  val initiator = "initiatorTest"
  val namespaceDemo = "demo"
  val namespaceGuest = "guest"
  val actionWithCustomPackage = "apimgmt/createApi"
  val actionWithDefaultPackage = "createApi"
  val kind = "nodejs:10"
  val memory = 256

  it should "push user events to kamon" in {
    createCustomTopic(EventConsumer.userEventTopic)

    val consumer = createConsumer(kafkaPort, system.settings.config, KamonRecorder)

    publishStringMessageToKafka(
      EventConsumer.userEventTopic,
      newActivationEvent(s"$namespaceDemo/$actionWithCustomPackage").serialize)
    publishStringMessageToKafka(
      EventConsumer.userEventTopic,
      newActivationEvent(s"$namespaceDemo/$actionWithDefaultPackage").serialize)

    publishStringMessageToKafka(
      EventConsumer.userEventTopic,
      newActivationEvent(s"$namespaceGuest/$actionWithDefaultPackage").serialize)

    sleep(sleepAfterProduce, "sleeping post produce")
    consumer.shutdown().futureValue
    sleep(4.second, "sleeping for Kamon reporters to get invoked")

    // Custom package
    TestReporter.counter(activationMetric, namespaceDemo, actionWithCustomPackage)(0).value shouldBe 1
    TestReporter
      .counter(activationMetric, namespaceDemo, actionWithCustomPackage)
      .filter((t) => t.tags.get(actionMemory).get == memory.toString)(0)
      .value shouldBe 1
    TestReporter
      .counter(activationMetric, namespaceDemo, actionWithCustomPackage)
      .filter((t) => t.tags.get(actionKind).get == kind)(0)
      .value shouldBe 1
    TestReporter
      .counter(statusMetric, namespaceDemo, actionWithCustomPackage)
      .filter((t) => t.tags.get(actionStatus).get == ActivationResponse.statusDeveloperError)(0)
      .value shouldBe 1
    TestReporter.counter(coldStartMetric, namespaceDemo, actionWithCustomPackage)(0).value shouldBe 1
    TestReporter.histogram(waitTimeMetric, namespaceDemo, actionWithCustomPackage).size shouldBe 1
    TestReporter.histogram(initTimeMetric, namespaceDemo, actionWithCustomPackage).size shouldBe 1
    TestReporter.histogram(durationMetric, namespaceDemo, actionWithCustomPackage).size shouldBe 1

    // Default package
    TestReporter.histogram(durationMetric, namespaceDemo, actionWithDefaultPackage).size shouldBe 1

    // Blacklisted namespace should not be tracked
    TestReporter.counter(activationMetric, namespaceGuest, actionWithDefaultPackage)(0).value shouldBe 0

    // Blacklisted should be counted in "openwhisk.namespace.activations" metric
    TestReporter.namespaceCounter(namespaceActivationMetric, namespaceGuest)(0).value shouldBe 1
  }

  private def newActivationEvent(actionPath: String) =
    EventMessage(
      "test",
      Activation(actionPath, 2, 3.millis, 5.millis, 11.millis, kind, false, memory, None),
      Subject("testuser"),
      initiator,
      UUID("test"),
      Activation.typeName)

  private object TestReporter extends MetricReporter {
    var snapshotAccumulator = new PeriodSnapshotAccumulator(Duration.ofDays(1), Duration.ZERO)
    override def reportPeriodSnapshot(snapshot: PeriodSnapshot): Unit = {
      snapshotAccumulator.add(snapshot)
    }

    override def start(): Unit = {}
    override def stop(): Unit = {}
    override def reconfigure(config: Config): Unit = {}

    def reset(): Unit = {
      snapshotAccumulator = new PeriodSnapshotAccumulator(Duration.ofDays(1), Duration.ZERO)
    }

    def counter(metricName: String, namespace: String, action: String) = {
      System.out.println()
      snapshotAccumulator
        .peek()
        .metrics
        .counters
        .filter(_.name == metricName)
        .filter((t) => t.tags.get(actionNamespace).get == namespace)
        .filter((t) => t.tags.get(initiatorNamespace).get == initiator)
        .filter((t) => t.tags.get(actionName).get == action)
    }

    def namespaceCounter(metricName: String, namespace: String) = {
      System.out.println()
      snapshotAccumulator
        .peek()
        .metrics
        .counters
        .filter(_.name == metricName)
        .filter((t) => t.tags.get(actionNamespace).get == namespace)
        .filter((t) => t.tags.get(initiatorNamespace).get == initiator)
    }

    def histogram(metricName: String, namespace: String, action: String) = {
      snapshotAccumulator
        .peek()
        .metrics
        .histograms
        .filter(_.name == metricName)
        .filter((t) => t.tags.get(actionNamespace).get == namespace)
        .filter((t) => t.tags.get(initiatorNamespace).get == initiator)
        .filter((t) => t.tags.get(actionName).get == action)
    }
  }
}

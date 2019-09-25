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
import net.manub.embeddedkafka.EmbeddedKafkaConfig
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterEach
import org.scalatest.junit.JUnitRunner
import org.apache.openwhisk.core.connector.{Activation, EventMessage}
import org.apache.openwhisk.core.entity.{ActivationResponse, Subject, UUID}

import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class KamonRecorderTests extends KafkaSpecBase with BeforeAndAfterEach with KamonMetricNames {
  val sleepAfterProduce: FiniteDuration = 4.seconds
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

  val namespace = "whisk.system"
  val initiator = "testNS"
  val actionWithCustomPackage = "apimgmt/createApi"
  val actionWithDefaultPackage = "createApi"
  val kind = "nodejs:10"
  val memory = 256

  it should "push user events to kamon" in {
    val kconfig = EmbeddedKafkaConfig(kafkaPort = 0, zooKeeperPort = 0)
    withRunningKafkaOnFoundPort(kconfig) { implicit actualConfig =>
      createCustomTopic(EventConsumer.userEventTopic)

      val consumer = createConsumer(actualConfig.kafkaPort, system.settings.config, KamonRecorder)

      publishStringMessageToKafka(
        EventConsumer.userEventTopic,
        newActivationEvent(s"$namespace/$actionWithCustomPackage").serialize)

      publishStringMessageToKafka(
        EventConsumer.userEventTopic,
        newActivationEvent(s"$namespace/$actionWithDefaultPackage").serialize)

      sleep(sleepAfterProduce, "sleeping post produce")
      consumer.shutdown().futureValue
      sleep(4.second, "sleeping for Kamon reporters to get invoked")

      // Custom package
      TestReporter.counter(activationMetric, actionWithCustomPackage).size shouldBe 1
      TestReporter
        .counter(activationMetric, actionWithCustomPackage)
        .filter((t) => t.tags.get(actionMemory).get == memory.toString)
        .size shouldBe 1
      TestReporter
        .counter(activationMetric, actionWithCustomPackage)
        .filter((t) => t.tags.get(actionKind).get == kind)
        .size shouldBe 1
      TestReporter
        .counter(statusMetric, actionWithCustomPackage)
        .filter((t) => t.tags.get(actionStatus).get == ActivationResponse.statusDeveloperError)
        .size shouldBe 1
      TestReporter.counter(coldStartMetric, actionWithCustomPackage).size shouldBe 1
      TestReporter.histogram(waitTimeMetric, actionWithCustomPackage).size shouldBe 1
      TestReporter.histogram(initTimeMetric, actionWithCustomPackage).size shouldBe 1
      TestReporter.histogram(durationMetric, actionWithCustomPackage).size shouldBe 1

      // Default package
      TestReporter.histogram(durationMetric, actionWithDefaultPackage).size shouldBe 1
    }
  }

  private def newActivationEvent(name: String) =
    EventMessage(
      namespace,
      Activation(name, 2, 3.millis, 5.millis, 11.millis, kind, false, memory, None),
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

    def counter(name: String, action: String) = {
      System.out.println()
      snapshotAccumulator
        .peek()
        .metrics
        .counters
        .filter(_.name == name)
        .filter((t) => t.tags.get(actionNamespace).get == namespace)
        .filter((t) => t.tags.get(initiatorNamespace).get == initiator)
        .filter((t) => t.tags.get(actionName).get == action)
    }

    def histogram(name: String, action: String) = {
      snapshotAccumulator
        .peek()
        .metrics
        .histograms
        .filter(_.name == name)
        .filter((t) => t.tags.get(actionNamespace).get == namespace)
        .filter((t) => t.tags.get(initiatorNamespace).get == initiator)
        .filter((t) => t.tags.get(actionName).get == action)
    }
  }
}

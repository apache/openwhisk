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

  it should "push user events to kamon" in {
    val kconfig = EmbeddedKafkaConfig(kafkaPort = 0, zooKeeperPort = 0)
    withRunningKafkaOnFoundPort(kconfig) { implicit actualConfig =>
      createCustomTopic(EventConsumer.userEventTopic)

      val consumer = createConsumer(actualConfig.kafkaPort, system.settings.config, KamonRecorder)
      publishStringMessageToKafka(
        EventConsumer.userEventTopic,
        newActivationEvent("whisk.system/apimgmt/createApi").serialize)

      sleep(sleepAfterProduce, "sleeping post produce")
      consumer.shutdown().futureValue
      sleep(1.second, "sleeping for Kamon reporters to get invoked")
      TestReporter.counter(activationMetric).get.value shouldBe 1
      TestReporter.counter(activationMetric).get.tags("namespace") shouldBe "whisk.system"
      TestReporter.counter(activationMetric).get.tags("initiator") shouldBe "testNS"
      TestReporter.counter(activationMetric).get.tags("action") shouldBe "apimgmt/createApi"
      TestReporter.counter(activationMetric).get.tags("kind") shouldBe "nodejs:6"
      TestReporter.counter(activationMetric).get.tags("memory") shouldBe "256"

      TestReporter.counter(statusMetric).get.tags.find(_._2 == ActivationResponse.statusDeveloperError).size shouldBe 1
      TestReporter.counter(coldStartMetric).get.value shouldBe 1
      TestReporter.counter(statusMetric).get.value shouldBe 1

      TestReporter.histogram(waitTimeMetric).get.distribution.count shouldBe 1
      TestReporter.histogram(initTimeMetric).get.distribution.count shouldBe 1
      TestReporter.histogram(durationMetric).get.distribution.count shouldBe 1
      TestReporter.histogram(durationMetric).get.tags("namespace") shouldBe "whisk.system"
      TestReporter.histogram(durationMetric).get.tags("initiator") shouldBe "testNS"
      TestReporter.histogram(durationMetric).get.tags("action") shouldBe "apimgmt/createApi"
    }
  }

  private def newActivationEvent(name: String, kind: String = "nodejs:6", initiator: String = "testNS") =
    EventMessage(
      "test",
      Activation(name, 2, 3.millis, 5.millis, 11.millis, kind, false, 256, None),
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

    def counter(name: String) = {
      snapshotAccumulator.peek().metrics.counters.find(_.name == name)
    }

    def histogram(name: String) = {
      snapshotAccumulator.peek().metrics.histograms.find(_.name == name)
    }
  }
}

/*
Copyright 2018 Adobe. All rights reserved.
This file is licensed to you under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License. You may obtain a copy
of the License at http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under
the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
OF ANY KIND, either express or implied. See the License for the specific language
governing permissions and limitations under the License.
 */

package com.adobe.api.platform.runtime.metrics

import java.time.Duration

import com.typesafe.config.{Config, ConfigFactory}
import kamon.metric.{PeriodSnapshot, PeriodSnapshotAccumulator}
import kamon.util.Registration
import kamon.{Kamon, MetricReporter}
import net.manub.embeddedkafka.EmbeddedKafkaConfig
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterEach
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class KamonConsumerTests extends KafkaSpecBase with BeforeAndAfterEach {
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
      createCustomTopic(KamonConsumer.userEventTopic)
      val consumer = createConsumer(actualConfig.kafkaPort)
      publishStringMessageToKafka(KamonConsumer.userEventTopic, newActivationEvent("a1").serialize)
      sleep(sleepAfterProduce, "sleeping post produce")
      consumer.shutdown().futureValue
      sleep(1.second, "sleeping for Kamon reporters to get invoked")
      TestReporter.counter("activations").get.value shouldBe 1
    }
  }

  private def createConsumer(kport: Int) = {
    val settings = OpenWhiskEvents
      .eventConsumerSettings(OpenWhiskEvents.defaultConsumerConfig(system))
      .withBootstrapServers(s"localhost:$kport")
    KamonConsumer(settings)
  }

  private def newActivationEvent(name: String, kind: String = "nodejs") =
    EventMessage(
      "test",
      Activation(name, 200, 3, 7, 11, kind, false, 256, None),
      "testuser",
      "testNS",
      "test",
      Activation.typeName)

  private object TestReporter extends MetricReporter {
    var snapshotAccumulator = new PeriodSnapshotAccumulator(Duration.ofDays(1), Duration.ZERO)
    override def reportPeriodSnapshot(snapshot: PeriodSnapshot): Unit = {
      snapshotAccumulator.add(snapshot)
    }

    override def start(): Unit = {}
    override def stop(): Unit = {}
    override def reconfigure(config: Config): Unit = {}

    def reset() = {
      snapshotAccumulator = new PeriodSnapshotAccumulator(Duration.ofDays(1), Duration.ZERO)
    }

    def counter(name: String) = {
      snapshotAccumulator.peek().metrics.counters.find(_.name == name)
    }
  }
}

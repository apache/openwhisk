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

package services

import java.io.File
import java.time.Instant

import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink}
import akka.testkit.TestProbe
import common.{StreamLogging, TestUtils, WhiskProperties, WskActorSystem}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import pureconfig.loadConfigOrThrow
import whisk.common.TransactionId
import whisk.connector.kafka.{KafkaConfig, KafkaMessagingProvider}
import whisk.core.WhiskConfig
import whisk.core.connector.Message
import whisk.utils.{retry, ExecutionContextFactory}

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.Try

@RunWith(classOf[JUnitRunner])
class KafkaConnectorTests extends FlatSpec with Matchers with WskActorSystem with BeforeAndAfterAll with StreamLogging {
  implicit val transid = TransactionId.testing
  implicit val ec = ExecutionContextFactory.makeCachedThreadPoolExecutionContext()

  implicit val materializer = ActorMaterializer()

  val kafkaConfig = loadConfigOrThrow[KafkaConfig]("whisk.kafka")
  val kafkaHosts = kafkaConfig.hosts.split(",")

  val groupid = "kafkatest"
  val topic = "Dinosaurs"
  val sessionTimeout = 10 seconds
  val maxPollInterval = 10 seconds
  val producer = KafkaMessagingProvider.getProducer()
  val consumer = KafkaMessagingProvider.getConsumer(groupid, topic, 1)

  override def afterAll() {
    producer.close()
    super.afterAll()
  }

  def commandComponent(host: String, command: String, component: String) = {
    def file(path: String) = Try(new File(path)).filter(_.exists).map(_.getAbsolutePath).toOption
    val docker = (file("/usr/bin/docker") orElse file("/usr/local/bin/docker")).getOrElse("docker")
    val dockerPort = WhiskProperties.getProperty(WhiskConfig.dockerPort)
    val cmd = Seq(docker, "--host", host + ":" + dockerPort, command, component)

    TestUtils.runCmd(0, new File("."), cmd: _*)
  }

  behavior of "Kafka connector"

  it should "send and receive a kafka message which sets up the topic" in {
    val probe = TestProbe()
    val killSwitch = consumer.toMat(Sink.actorRef(probe.ref, ()))(Keep.left).run()

    for (i <- 0 until 5) {
      val message = new Message { override val serialize = Instant.now.toString }

      producer.send(topic, message)
      probe.expectMsg(message.serialize)
    }

    killSwitch.shutdown()
  }

  it should "send and receive a kafka message even after shutdown one of instances" in {

    val probe = TestProbe()
    val killSwitch = consumer.toMat(Sink.actorRef(probe.ref, ()))(Keep.left).run()

    for (i <- 0 until kafkaHosts.length) {
      def message = new Message {
        override val serialize = Instant.now.toString
      }

      val kafkaHost = kafkaHosts(i).split(":")(0)
      val startLog = ", started"
      val prevCount = startLog.r.findAllMatchIn(commandComponent(kafkaHost, "logs", s"kafka$i").stdout).length

      println(s"Stop kafka$i now")
      commandComponent(kafkaHost, "stop", s"kafka$i")

      // With only 1 Kafka, it will not be possible to send and receive messages
      if (kafkaHosts.length > 1) {
        val firstMessage = message
        producer.send(topic, firstMessage)
        probe.expectMsg(firstMessage.serialize)
      }

      println(s"Start kafka$i now")
      commandComponent(kafkaHost, "start", s"kafka$i")
      retry({
        startLog.r
          .findAllMatchIn(commandComponent(kafkaHost, "logs", s"kafka$i").stdout)
          .length shouldBe prevCount + 1
      }, 20, Some(1.second)) // wait until kafka is up
      println(s"kafka$i started")

      val secondMessage = message
      Await.result(producer.send(topic, secondMessage), 1.minute)
      probe.expectMsg(2.minutes, secondMessage.serialize)

      killSwitch.shutdown()
    }
  }
}

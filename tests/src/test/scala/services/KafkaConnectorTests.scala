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
import java.nio.charset.StandardCharsets
import java.util.Calendar

import common.{StreamLogging, TestUtils, WhiskProperties, WskActorSystem}
import ha.ShootComponentUtils
import org.apache.kafka.clients.consumer.CommitFailedException
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.connector.kafka.{KafkaConsumerConnector, KafkaMessagingProvider, KafkaProducerConnector}
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.connector.Message
import org.apache.openwhisk.utils.{retry, ExecutionContextFactory}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext}
import scala.language.postfixOps
import scala.util.Try

@RunWith(classOf[JUnitRunner])
class KafkaConnectorTests
    extends FlatSpec
    with Matchers
    with WskActorSystem
    with BeforeAndAfterAll
    with StreamLogging
    with ShootComponentUtils {
  implicit val transid: TransactionId = TransactionId.testing
  implicit val ec: ExecutionContext = ExecutionContextFactory.makeCachedThreadPoolExecutionContext()

  val config = new WhiskConfig(WhiskConfig.kafkaHosts)
  assert(config.isValid)

  val groupid = "kafkatest"
  val topic = "KafkaConnectorTestTopic"
  val maxPollInterval = 10.seconds
  System.setProperty("whisk.kafka.consumer.max-poll-interval-ms", maxPollInterval.toMillis.toString)

  // Need to overwrite replication factor for tests that shut down and start
  // Kafka instances intentionally. These tests will fail if there is more than
  // one Kafka host but a replication factor of 1.
  val kafkaHosts: Array[String] = config.kafkaHosts.split(",")
  val replicationFactor: Int = kafkaHosts.length / 2 + 1
  System.setProperty("whisk.kafka.replication-factor", replicationFactor.toString)

  println(s"Create test topic '$topic' with replicationFactor=$replicationFactor")
  KafkaMessagingProvider.ensureTopic(config, topic, topic) shouldBe 'success

  val producer = new KafkaProducerConnector(config.kafkaHosts)
  val consumer = new KafkaConsumerConnector(config.kafkaHosts, groupid, topic)

  override def afterAll(): Unit = {
    producer.close()
    consumer.close()
    super.afterAll()
  }

  def commandComponent(host: String, command: String, component: String): TestUtils.RunResult = {
    def file(path: String) = Try(new File(path)).filter(_.exists).map(_.getAbsolutePath).toOption
    val docker = (file("/usr/bin/docker") orElse file("/usr/local/bin/docker")).getOrElse("docker")
    val dockerPort = WhiskProperties.getProperty(WhiskConfig.dockerPort)
    val cmd = Seq(docker, "--host", host + ":" + dockerPort, command, component)

    TestUtils.runCmd(0, new File("."), cmd: _*)
  }

  def sendAndReceiveMessage(message: Message,
                            waitForSend: FiniteDuration,
                            waitForReceive: FiniteDuration): Iterable[String] = {
    retry {
      val start = java.lang.System.currentTimeMillis
      println(s"Send message to topic.")
      val sent = Await.result(producer.send(topic, message), waitForSend)
      println(s"Successfully sent message to topic: $sent")
      println(s"Receiving message from topic.")
      val received =
        consumer.peek(waitForReceive).map { case (_, _, _, msg) => new String(msg, StandardCharsets.UTF_8) }
      val end = java.lang.System.currentTimeMillis
      val elapsed = end - start
      println(s"Received ${received.size}. Took $elapsed msec: $received")

      received.last should be(message.serialize)
      received
    }
  }

  def createMessage(): Message = new Message { override val serialize: String = Calendar.getInstance.getTime.toString }

  behavior of "Kafka connector"

  it should "send and receive a kafka message which sets up the topic" in {
    for (i <- 0 until 5) {
      val message = createMessage()
      val received = sendAndReceiveMessage(message, 20 seconds, 10 seconds)
      received.size should be >= 1
      consumer.commit()
    }
  }

  it should "send and receive a kafka message even after session timeout" in {
    // "clear" the topic so there are 0 messages to be read
    sendAndReceiveMessage(createMessage(), 1 seconds, 1 seconds)
    consumer.commit()

    (1 to 2).foreach { i =>
      val message = createMessage()
      val received = sendAndReceiveMessage(message, 1 seconds, 1 seconds)
      received.size shouldBe i // should accumulate since the commits fail

      Thread.sleep((maxPollInterval + 1.second).toMillis)
      a[CommitFailedException] should be thrownBy consumer.commit()
    }

    val message3 = createMessage()
    val received3 = sendAndReceiveMessage(message3, 1 seconds, 1 seconds)
    received3.size shouldBe 2 + 1 // since the last commit still failed
    consumer.commit()

    val message4 = createMessage()
    val received4 = sendAndReceiveMessage(message4, 1 seconds, 1 seconds)
    received4.size shouldBe 1
    consumer.commit()
  }

  if (kafkaHosts.length > 1) {
    it should "send and receive a kafka message even after shutdown one of instances" in {
      kafkaHosts.indices.foreach { i =>
        val message = createMessage()
        val kafkaHost = kafkaHosts(i).split(":")(0)
        val startLog = s"\\[KafkaServer id=$i\\] started"
        val prevCount = startLog.r.findAllMatchIn(commandComponent(kafkaHost, "logs", s"kafka$i").stdout).length

        // 1. stop one of kafka node
        stopComponent(kafkaHost, s"kafka$i")

        // 2. kafka cluster should be ok at least after three retries
        retry({
          val received = sendAndReceiveMessage(message, 40 seconds, 40 seconds)
          received.size should be >= 1
        }, 3, Some(100.milliseconds))
        consumer.commit()

        // 3. recover stopped node
        startComponent(kafkaHost, s"kafka$i")

        // 4. wait until kafka is up
        retry({
          startLog.r
            .findAllMatchIn(commandComponent(kafkaHost, "logs", s"kafka$i").stdout)
            .length shouldBe prevCount + 1
        }, 20, Some(1.second))

        // 5. kafka cluster should be ok at least after three retires
        retry({
          val received = sendAndReceiveMessage(message, 40 seconds, 40 seconds)
          received.size should be >= 1
        }, 3, Some(100.milliseconds))
        consumer.commit()
      }
    }
  }
}

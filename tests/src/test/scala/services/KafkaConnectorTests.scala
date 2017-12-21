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
import java.util.Calendar

import scala.concurrent.Await
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.language.postfixOps
import scala.util.Try
import org.apache.kafka.clients.consumer.CommitFailedException
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import common.{StreamLogging, TestUtils, WhiskProperties, WskActorSystem}
import whisk.common.TransactionId
import whisk.connector.kafka.KafkaConsumerConnector
import whisk.connector.kafka.KafkaProducerConnector
import whisk.core.WhiskConfig
import whisk.core.connector.Message
import whisk.utils.ExecutionContextFactory
import whisk.utils.retry

@RunWith(classOf[JUnitRunner])
class KafkaConnectorTests extends FlatSpec with Matchers with WskActorSystem with BeforeAndAfterAll with StreamLogging {
  implicit val transid = TransactionId.testing
  implicit val ec = ExecutionContextFactory.makeCachedThreadPoolExecutionContext()

  val config = new WhiskConfig(WhiskConfig.kafkaHosts)
  assert(config.isValid)

  val groupid = "kafkatest"
  val topic = "Dinosaurs"
  val sessionTimeout = 10 seconds
  val maxPollInterval = 10 seconds
  val producer = new KafkaProducerConnector(config.kafkaHosts, ec)
  val consumer = new KafkaConsumerConnector(
    config.kafkaHosts,
    groupid,
    topic,
    sessionTimeout = sessionTimeout,
    maxPollInterval = maxPollInterval)

  override def afterAll() {
    producer.close()
    consumer.close()
    super.afterAll()
  }

  def commandComponent(host: String, command: String, component: String) = {
    def file(path: String) = Try(new File(path)).filter(_.exists).map(_.getAbsolutePath).toOption
    val docker = (file("/usr/bin/docker") orElse file("/usr/local/bin/docker")).getOrElse("docker")
    val dockerPort = WhiskProperties.getProperty(WhiskConfig.dockerPort)
    val cmd = Seq(docker, "--host", host + ":" + dockerPort, command, component)

    TestUtils.runCmd(0, new File("."), cmd: _*)
  }

  def sendAndReceiveMessage(message: Message,
                            waitForSend: FiniteDuration,
                            waitForReceive: FiniteDuration): Iterable[String] = {
    val start = java.lang.System.currentTimeMillis
    println(s"Send message to topic.\n")
    val sent = Await.result(producer.send(topic, message), waitForSend)
    println(s"Successfully sent message to topic: ${sent}\n")
    println(s"Receiving message from topic.\n")
    val received = consumer.peek(waitForReceive).map { case (_, _, _, msg) => new String(msg, "utf-8") }
    val end = java.lang.System.currentTimeMillis
    val elapsed = end - start
    println(s"Received ${received.size}. Took $elapsed msec: $received\n")

    received
  }

  behavior of "Kafka connector"

  it should "send and receive a kafka message which sets up the topic" in {
    for (i <- 0 until 5) {
      val message = new Message { override val serialize = Calendar.getInstance().getTime().toString }
      val received = sendAndReceiveMessage(message, 20 seconds, 10 seconds)
      received.size should be >= 1
      received.last should be(message.serialize)
      consumer.commit()
    }
  }

  it should "send and receive a kafka message even after session timeout" in {
    for (i <- 0 until 4) {
      val message = new Message { override val serialize = Calendar.getInstance().getTime().toString }
      val received = sendAndReceiveMessage(message, 1 seconds, 1 seconds)

      // only the last iteration will have an updated cursor
      // iteration 0: get whatever is on the topic (at least 1 but may be more if a previous test failed)
      // iteration 1: get iteration 0 records + 1 more (since we intentionally failed the commit on previous iteration)
      // iteration 2: get iteration 1 records + 1 more (since we intentionally failed the commit on previous iteration)
      // iteration 3: get exactly 1 records since iteration 2 should have forwarded the cursor
      if (i < 3) {
        received.size should be >= i + 1
      } else {
        received.size should be(1)
      }
      received.last should be(message.serialize)

      if (i < 2) {
        Thread.sleep((maxPollInterval + 1.second).toMillis)
        a[CommitFailedException] should be thrownBy {
          consumer.commit() // sleep should cause commit to fail
        }
      } else consumer.commit()
    }
  }

  it should "send and receive a kafka message even after shutdown one of instances" in {
    val kafkaHosts = config.kafkaHosts.split(",")
    if (kafkaHosts.length > 1) {
      for (i <- 0 until kafkaHosts.length) {
        val message = new Message { override val serialize = Calendar.getInstance().getTime().toString }
        val kafkaHost = kafkaHosts(i).split(":")(0)
        val startLog = s", started"
        val prevCount = startLog.r.findAllMatchIn(commandComponent(kafkaHost, "logs", s"kafka$i").stdout).length

        commandComponent(kafkaHost, "stop", s"kafka$i")
        var received = sendAndReceiveMessage(message, 30 seconds, 30 seconds)
        received.size should be(1)
        consumer.commit()

        commandComponent(kafkaHost, "start", s"kafka$i")
        retry({
          startLog.r
            .findAllMatchIn(commandComponent(kafkaHost, "logs", s"kafka$i").stdout)
            .length shouldBe prevCount + 1
        }, 20, Some(1.second)) // wait until kafka is up

        received = sendAndReceiveMessage(message, 30 seconds, 30 seconds)
        received.size should be(1)
        consumer.commit()
      }
    }
  }
}

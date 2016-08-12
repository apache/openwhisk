/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package services

import java.util.Calendar

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import org.apache.kafka.clients.consumer.CommitFailedException
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import akka.event.Logging.DebugLevel
import whisk.common.TransactionId
import whisk.connector.kafka.KafkaConsumerConnector
import whisk.connector.kafka.KafkaProducerConnector
import whisk.core.WhiskConfig
import whisk.core.connector.Message
import whisk.utils.ExecutionContextFactory

@RunWith(classOf[JUnitRunner])
class KafkaConnectorTests extends FlatSpec with Matchers with BeforeAndAfterAll {
    implicit val transid = TransactionId.testing
    implicit val ec = ExecutionContextFactory.makeCachedThreadPoolExecutionContext()

    val config = new WhiskConfig(WhiskConfig.kafkaHost)
    assert(config.isValid)

    val groupid = "kafkatest"
    val topic = "Dinosaurs"
    val sessionTimeout = 10 seconds
    val producer = new KafkaProducerConnector(config.kafkaHost, ec)
    val consumer = new KafkaConsumerConnector(config.kafkaHost, groupid, topic, sessionTimeout = sessionTimeout)

    producer.setVerbosity(DebugLevel)
    consumer.setVerbosity(DebugLevel)

    override def afterAll() {
        producer.close()
        consumer.close()
    }

    behavior of "Kafka connector"

    it should "send and receive a kafka message which sets up the topic" in {
        for (i <- 0 until 5) {
            val message = new Message { override val serialize = Calendar.getInstance().getTime().toString }
            val start = java.lang.System.currentTimeMillis
            val sent = Await.result(producer.send(topic, message), 10 seconds)
            val received = consumer.peek(10 seconds).map { case (_, _, _, msg) => new String(msg, "utf-8") }
            val end = java.lang.System.currentTimeMillis
            val elapsed = end - start
            println(s"($i) Received ${received.size}. Took $elapsed msec: $received\n")
            received.size should be >= 1
            received.last should be(message.serialize)
            consumer.commit()
        }
    }

    it should "send and receive a kafka message even after session timeout" in {
        for (i <- 0 until 4) {
            val message = new Message { override val serialize = Calendar.getInstance().getTime().toString }
            val start = java.lang.System.currentTimeMillis
            val sent = Await.result(producer.send(topic, message), 1 seconds)
            val received = consumer.peek(1 seconds).map { case (_, _, _, msg) => new String(msg, "utf-8") }
            val end = java.lang.System.currentTimeMillis
            val elapsed = end - start
            println(s"($i) Received ${received.size}. Took $elapsed msec: $received\n")

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
                Thread.sleep((sessionTimeout + 1.second).toMillis)
                an[CommitFailedException] should be thrownBy {
                    consumer.commit() // sleep should cause commit to fail
                }
            } else consumer.commit()
        }
    }

}

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

import scala.collection.JavaConversions.iterableAsScalaIterable
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfter
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import whisk.common.TransactionId
import whisk.common.Verbosity
import whisk.connector.kafka.KafkaConsumerConnector
import whisk.connector.kafka.KafkaProducerConnector
import whisk.core.WhiskConfig
import whisk.core.connector.Message
import whisk.utils.ExecutionContextFactory

@RunWith(classOf[JUnitRunner])
class KafkaConnectorTests extends FlatSpec with Matchers with BeforeAndAfter {
    implicit val transid = TransactionId.testing
    implicit val ec = ExecutionContextFactory.makeSingleThreadExecutionContext()

    behavior of "Kafka connector"

    it should "send and receive a kafka message" in {
        val config = new WhiskConfig(WhiskConfig.kafkaHost)
        assert(config.isValid)

        val groupid = "kafkatest"
        val topic = "Dinosaurs"
        val producer = new KafkaProducerConnector(config.kafkaHost, ec)
        val consumer = new KafkaConsumerConnector(config.kafkaHost, groupid, topic)
        producer.setVerbosity(Verbosity.Debug)
        consumer.setVerbosity(Verbosity.Debug)

        try {
            val message = new Message { override val serialize = Calendar.getInstance().getTime().toString }
            val sent = Await.result(producer.send(topic, message), 10 seconds)
            val received = consumer.getMessages(10 seconds).map { r => new String(r.value, "utf-8") }
            println(received)
            received.size should be >= 1
            received.last should be(message.serialize)
        } finally {
            producer.close()
            consumer.close()
        }
    }
}

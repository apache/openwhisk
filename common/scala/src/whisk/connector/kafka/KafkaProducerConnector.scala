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

package whisk.connector.kafka

import java.util.Properties
import java.util.UUID

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success

import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.serialization.StringSerializer

import whisk.common.Counter
import whisk.common.Logging
import whisk.core.connector.Message
import whisk.core.connector.MessageProducer

class KafkaProducerConnector(
    kafkahost: String,
    implicit val executionContext: ExecutionContext,
    id: String = UUID.randomUUID().toString)
    extends MessageProducer with Logging {

    override def sentCount() = sentCounter.cur

    /** Sends msg to topic. This is an asynchronous operation. */
    override def send(topic: String, msg: Message): Future[RecordMetadata] = {
        implicit val transid = msg.transid
        val record = new ProducerRecord[String, String](topic, "messages", msg.serialize)
        val promise = Promise[RecordMetadata]
        Future {
            debug(this, s"sending to topic '$topic' msg '$msg'")
            producer.send(record).get
        } onComplete {
            case Success(status) =>
                info(this, s"sent message: ${status.topic()}[${status.partition()}][${status.offset()}]")
                sentCounter.next()
                promise.success(status)
            case Failure(t) =>
                error(this, s"sending message on topic '$topic' failed: ${t.getMessage}")
                promise.failure(t)
        }
        promise.future
    }

    /** Closes producer. */
    override def close() = {
        info(this, "closing producer")
        producer.close()
    }

    private val sentCounter = new Counter()

    private def getProps: Properties = {
        val props = new Properties
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkahost)
        props.put(ProducerConfig.ACKS_CONFIG, 1.toString)
        props
    }

    private def getProducer(props: Properties): KafkaProducer[String, String] = {
        val keySerializer = new StringSerializer
        val valueSerializer = new StringSerializer
        new KafkaProducer(props, keySerializer, valueSerializer)
    }

    private val producer = getProducer(getProps)
}

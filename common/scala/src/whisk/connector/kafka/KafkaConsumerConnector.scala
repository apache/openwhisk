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

import scala.collection.JavaConversions.seqAsJavaList
import scala.util.Failure
import scala.util.Success

import kafka.consumer.Consumer
import kafka.consumer.ConsumerConfig
import kafka.consumer.ConsumerConnector
import kafka.consumer.KafkaStream
import kafka.consumer.Whitelist
import kafka.serializer.DefaultDecoder
import whisk.common.Logging
import whisk.common.TransactionCounter
import whisk.core.connector.Message
import whisk.core.connector.MessageConsumer

class KafkaConsumerConnector(
    zookeeper: String,
    groupid: String,
    topic: String,
    readeos: Boolean = true)
    extends MessageConsumer
    with Logging
    with TransactionCounter {

    override def onMessage(process: Message => Unit) = {
        val self = this
        val thread = new Thread() {
            override def run() = {
                for (metadata <- stream) {
                    val raw = new String(metadata.message, "utf-8")
                    val msg = Message(raw)
                    msg match {
                        case Success(m) =>
                            info(self, s"received /${metadata.topic}[${metadata.partition}][${metadata.offset}]: ${m.path}")(m.transid)
                            process(m)
                        case Failure(t) =>
                            info(this, errorMsg(raw, t))
                    }
                }
                warn(self, "consumer stream terminated")
            }
        }
        thread.start()
    }

    override def close() = {
        info(this, s"closing /$topic")
        consumer.shutdown()
    }

    private def getProps: Properties = {
        val props = new Properties
        props.put("group.id", groupid)
        props.put("zookeeper.connect", zookeeper)
        props.put("auto.offset.reset", if (!readeos) "smallest" else "largest")
        props
    }

    private def getConsumer(props: Properties): ConsumerConnector = {
        val config = new ConsumerConfig(props)
        val consumer = Consumer.create(config)
        consumer
    }

    private def getStream(consumer: ConsumerConnector, topic: String): KafkaStream[Array[Byte], Array[Byte]] = {
        val filterSpec = new Whitelist(topic)
        val stream = consumer.createMessageStreamsByFilter(filterSpec, 1, new DefaultDecoder, new DefaultDecoder).get(0)
        stream
    }

    private def errorMsg(msg: String, e: Throwable) =
        s"failed processing message: $msg\n$e${e.getStackTrace.mkString("", " ", "")}"

    private val consumer = getConsumer(getProps)
    private val stream = getStream(consumer, topic)
}
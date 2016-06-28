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
import scala.language.postfixOps

import scala.collection.JavaConversions.iterableAsScalaIterable
import scala.collection.JavaConversions.seqAsJavaList
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Try

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.ByteArrayDeserializer

import whisk.common.Logging
import whisk.common.TransactionCounter
import whisk.core.connector.MessageConsumer

class KafkaConsumerConnector(
    kafkahost: String,
    groupid: String,
    topic: String,
    readeos: Boolean = true)
    extends MessageConsumer
    with Logging
    with TransactionCounter {

    /**
     * Long poll for messages. Method returns once message are available but no later than given
     * duration.
     *
     * @param duration the maximum duration for the long poll
     * @param syncCommit update offsets on the topic immediately.  Default false as we use auto-commit.
     */
    def getMessages(duration: Duration = 500 milliseconds, syncCommit: Boolean = false) = {
        val records = consumer.poll(duration.toMillis)
        if (syncCommit) commit()
        records
    }

    /**
     * Commits offsets from last poll.
     */
    def commit() = consumer.commitSync()

    override def onMessage(process: (String, Array[Byte]) => Boolean) = {
        val self = this
        val thread = new Thread() {
            override def run() = {
                while (!disconnect) {
                    Try { getMessages() } map {
                        records =>
                            val count = records.size
                            records foreach { r =>
                                val topic = r.topic
                                val partition = r.partition
                                val offset = r.offset
                                val msg = r.value
                                info(self, s"processing $topic[$partition][$offset ($count)]")
                                val consumed = process(topic, msg)
                            }
                            commit()
                    } match {
                        case Failure(t) => error(self, s"while polling: $t")
                        case _          => // ok
                    }
                }
                warn(self, "consumer stream terminated")
                consumer.close()
            }
        }
        thread.start()
    }

    override def close() = {
        info(this, s"closing '$topic' consumer")
        disconnect = true
    }

    private def getProps: Properties = {
        val props = new Properties
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupid)
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkahost)
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "10000");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true.toString)
        props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "10000");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, if (!readeos) "latest" else "earliest")

        // This value controls the server-side wait time which affects polling latency.
        // A low value improves latency performance but it is important to not set it too low
        // as that will cause excessive busy-waiting.
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, "20")

        props
    }

    /** Creates a new kafka consumer and subscribes to topic list if given. */
    private def getConsumer(props: Properties, topics: Option[List[String]] = None) = {
        val keyDeserializer = new ByteArrayDeserializer
        val valueDeserializer = new ByteArrayDeserializer
        val consumer = new KafkaConsumer(props, keyDeserializer, valueDeserializer)
        topics map { consumer.subscribe(_) }
        consumer
    }

    private val consumer = getConsumer(getProps, Some(List(topic)))
    private var disconnect = false
}

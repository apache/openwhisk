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

package whisk.connector.kafka

import java.util.Properties

import scala.collection.JavaConversions.iterableAsScalaIterable
import scala.collection.JavaConversions.seqAsJavaList
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.ByteArrayDeserializer

import whisk.common.Logging
import whisk.core.connector.MessageConsumer

class KafkaConsumerConnector(kafkahost: String,
                             groupid: String,
                             topic: String,
                             override val maxPeek: Int = Int.MaxValue,
                             readeos: Boolean = true,
                             sessionTimeout: FiniteDuration = 30.seconds,
                             autoCommitInterval: FiniteDuration = 10.seconds,
                             maxPollInterval: FiniteDuration = 5.minutes)(implicit logging: Logging)
    extends MessageConsumer {

  /**
   * Long poll for messages. Method returns once message are available but no later than given
   * duration.
   *
   * @param duration the maximum duration for the long poll
   */
  override def peek(duration: Duration = 500.milliseconds) = {
    val records = consumer.poll(duration.toMillis)
    records map { r =>
      (r.topic, r.partition, r.offset, r.value)
    }
  }

  /**
   * Commits offsets from last poll.
   */
  def commit() = consumer.commitSync()

  override def close() = {
    logging.info(this, s"closing '$topic' consumer")
  }

  private def getProps: Properties = {
    val props = new Properties
    props.put(ConsumerConfig.GROUP_ID_CONFIG, groupid)
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkahost)
    props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, sessionTimeout.toMillis.toString)
    props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, (sessionTimeout.toMillis / 3).toString)
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true.toString)
    props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, autoCommitInterval.toMillis.toString)
    props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPeek.toString)
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, if (!readeos) "latest" else "earliest")
    props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, maxPollInterval.toMillis.toString)

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
    topics foreach { consumer.subscribe(_) }
    consumer
  }

  private val consumer = getConsumer(getProps, Some(List(topic)))
}

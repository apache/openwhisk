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
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import pureconfig.loadConfigOrThrow
import whisk.common.Logging
import whisk.core.ConfigKeys
import whisk.core.connector.MessageConsumer

class KafkaConsumerConnector(kafkahost: String,
                             groupid: String,
                             topic: String,
                             override val maxPeek: Int = Int.MaxValue)(implicit logging: Logging)
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
    props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPeek.toString)

    val config =
      KafkaConfiguration.configMapToKafkaConfig(loadConfigOrThrow[Map[String, String]](ConfigKeys.kafkaCommon)) ++
        KafkaConfiguration.configMapToKafkaConfig(loadConfigOrThrow[Map[String, String]](ConfigKeys.kafkaConsumer))
    config.foreach {
      case (key, value) => props.put(key, value)
    }
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

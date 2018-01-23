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
import java.util.concurrent.ExecutionException

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.collection.JavaConverters._

import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.errors.TopicExistsException

import whisk.common.Logging
import whisk.core.ConfigKeys
import whisk.core.WhiskConfig
import whisk.core.connector.MessageConsumer
import whisk.core.connector.MessageProducer
import whisk.core.connector.MessagingProvider

import pureconfig._

case class KafkaConfig(replicationFactor: Short)

/**
 * A Kafka based implementation of MessagingProvider
 */
object KafkaMessagingProvider extends MessagingProvider {
  def getConsumer(config: WhiskConfig, groupId: String, topic: String, maxPeek: Int, maxPollInterval: FiniteDuration)(
    implicit logging: Logging): MessageConsumer =
    new KafkaConsumerConnector(config.kafkaHosts, groupId, topic, maxPeek, maxPollInterval = maxPollInterval)

  def getProducer(config: WhiskConfig, ec: ExecutionContext)(implicit logging: Logging): MessageProducer =
    new KafkaProducerConnector(config.kafkaHosts, ec)

  def ensureTopic(config: WhiskConfig, topic: String, topicConfig: String)(implicit logging: Logging): Boolean = {
    val kc = loadConfigOrThrow[KafkaConfig](ConfigKeys.kafka)
    val tc = KafkaConfiguration.configMapToKafkaConfig(
      loadConfigOrThrow[Map[String, String]](ConfigKeys.kafkaTopics + s".$topicConfig"))
    val props = new Properties
    props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, config.kafkaHosts)
    val client = AdminClient.create(props)
    val numPartitions = 1
    val nt = new NewTopic(topic, numPartitions, kc.replicationFactor).configs(tc.asJava)
    val results = client.createTopics(List(nt).asJava)
    try {
      results.values().get(topic).get()
      logging.info(this, s"created topic $topic")
      true
    } catch {
      case e: ExecutionException if e.getCause.isInstanceOf[TopicExistsException] =>
        logging.info(this, s"topic $topic already existed")
        true
      case e: Exception =>
        logging.error(this, s"ensureTopic for $topic failed due to $e")
        false
    } finally {
      client.close()
    }
  }
}

object KafkaConfiguration {
  def configToKafkaKey(configKey: String) = configKey.replace("-", ".")

  def configMapToKafkaConfig(configMap: Map[String, String]) = configMap.map {
    case (key, value) => configToKafkaKey(key) -> value
  }
}

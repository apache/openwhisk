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

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import whisk.common.Logging
import whisk.core.WhiskConfig
import whisk.core.connector.MessageConsumer
import whisk.core.connector.MessageProducer
import whisk.core.connector.MessagingProvider

/**
 * A Kafka based implementation of MessagingProvider
 */
object KafkaMessagingProvider extends MessagingProvider {
    def getConsumer(config: WhiskConfig, groupId: String, topic: String, maxPeek: Int, maxPollInterval: FiniteDuration)(implicit logging: Logging): MessageConsumer =
        new KafkaConsumerConnector(config.kafkaHost, groupId, topic, maxPeek, maxPollInterval = maxPollInterval)

    def getProducer(config: WhiskConfig, ec: ExecutionContext)(implicit logging: Logging): MessageProducer =
        new KafkaProducerConnector(config.kafkaHost, ec)
}

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

import akka.actor.ActorSystem
import scaldi.Injector
import whisk.common.Logging
import whisk.core.WhiskConfig
import whisk.core.connector.MessageConsumer
import whisk.core.connector.MessageProducer
import whisk.core.connector.MessagingProvider
import whisk.spi.SpiFactoryModule

/**
 * A Kafka based implementation of MessagingProvider
 */
class KafkaMessagingProvider(actorSystem: ActorSystem, config: WhiskConfig)(implicit logging: Logging) extends MessagingProvider {
    def getConsumer(groupId: String, topic: String, maxdepth: Int): MessageConsumer = {
        new KafkaConsumerConnector(config.kafkaHost, groupId, topic, maxdepth)(logging)
    }

    def getProducer(): MessageProducer = new KafkaProducerConnector(config.kafkaHost, actorSystem.dispatcher)
}

class KafkaMessagingProviderModule extends SpiFactoryModule[MessagingProvider] {
    def getInstance(implicit injector: Injector): MessagingProvider = {
        val actorSystem = inject[ActorSystem]
        val config = inject[WhiskConfig]
        implicit val logging = inject[Logging]
        new KafkaMessagingProvider(actorSystem, config)
    }
}

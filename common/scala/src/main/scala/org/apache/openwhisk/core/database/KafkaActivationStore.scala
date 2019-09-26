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

package org.apache.openwhisk.core.database

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.google.common.base.Throwables
import com.typesafe.config.Config
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import org.apache.openwhisk.core.connector.{MessageProducer, MessagingProvider, ResultMessage}
import org.apache.openwhisk.core.entity.{ActivationEntityLimit, ByteSize, DocInfo, WhiskActivation}
import org.apache.openwhisk.spi.{SimpleResolver, SpiClassResolver, SpiLoader}
import org.apache.openwhisk.core.entity.size._
import pureconfig.loadConfigOrThrow

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

case class KafkaActivationStoreConfig(activationsTopic: String,
                                      primaryStoreProvider: String,
                                      storeInPrimary: Boolean,
                                      maxRequestSize: Option[ByteSize])

class KafkaActivationStore(primary: ActivationStore,
                           producer: MessageProducer,
                           config: KafkaActivationStoreConfig,
                           actorSystem: ActorSystem,
                           actorMaterializer: ActorMaterializer,
                           logging: Logging)
    extends ActivationStoreWrapper(primary) {
  private implicit val executionContext: ExecutionContext = actorSystem.dispatcher

  override def store(activation: WhiskActivation, context: UserContext)(
    implicit transid: TransactionId,
    notifier: Option[CacheChangeNotification]): Future[DocInfo] = {
    if (config.storeInPrimary) {
      sendToKafka(activation).flatMap(_ => primary.store(activation, context))
    } else {
      sendToKafka(activation)
    }
  }

  private def sendToKafka(activation: WhiskActivation)(implicit transid: TransactionId) = {
    val msg = ResultMessage(transid, activation)
    producer
      .send(topic = config.activationsTopic, msg)
      .andThen {
        case Success(_) =>
          logging.info(
            this,
            s"posted activation to Kafka topic ${config.activationsTopic} - ${activation.activationId}")
        case Failure(exception) =>
          logging.warn(
            this,
            s"Failed to send activation to ${config.activationsTopic} - ${activation.activationId}" + Throwables
              .getStackTraceAsString(exception))
      }
      // returned DocInfo is currently not consumed anyway. So returning an info without the revId
      .map(_ => DocInfo(activation.docid))
  }
}

object KafkaActivationStoreProvider extends ActivationStoreProvider {
  override def instance(actorSystem: ActorSystem,
                        actorMaterializer: ActorMaterializer,
                        logging: Logging): ActivationStore = {
    val storeConfig = loadConfigOrThrow[KafkaActivationStoreConfig](ConfigKeys.kafkaActivationStore)
    implicit val spiResolver: SpiClassResolver = new SimpleResolver(storeConfig.primaryStoreProvider)
    val primaryStore = SpiLoader
      .get[ActivationStoreProvider]
      .instance(actorSystem, actorMaterializer, logging)
    val producer = createProducer(storeConfig)(logging, actorSystem)
    new KafkaActivationStore(primaryStore, producer, storeConfig, actorSystem, actorMaterializer, logging)
  }

  def createProducer(config: KafkaActivationStoreConfig)(implicit logging: Logging,
                                                         system: ActorSystem): MessageProducer = {
    val msgProvider = SpiLoader.get[MessagingProvider]
    val whiskConfig = new WhiskConfig(WhiskConfig.kafkaHosts)

    val topic = config.activationsTopic
    val topicConfig = getTopicConfigKey(topic, system.settings.config)
    //For this completed topic we do not specify any limit
    val ensureTopic = msgProvider.ensureTopic(whiskConfig, topic, topicConfig, maxMessageBytes = None)
    require(ensureTopic.isSuccess, s"Unable to create topic $topic with config " + ensureTopic)

    val maxSize = config.maxRequestSize.getOrElse(ActivationEntityLimit.MAX_ACTIVATION_LIMIT)
    msgProvider.getProducer(whiskConfig, Some(maxSize))
  }

  def getTopicConfigKey(topic: String, config: Config): String = {
    val key = ConfigKeys.kafkaTopics + "." + topic
    if (config.hasPath(key)) topic else "completed"
  }
}

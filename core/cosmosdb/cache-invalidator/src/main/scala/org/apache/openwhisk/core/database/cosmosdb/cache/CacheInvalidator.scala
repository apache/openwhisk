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
package org.apache.openwhisk.core.database.cosmosdb.cache

import akka.Done
import akka.actor.{ActorSystem, CoordinatedShutdown}
import akka.kafka.ProducerSettings
import akka.stream.ActorMaterializer
import com.google.common.base.Throwables
import com.typesafe.config.Config
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.core.database.RemoteCacheInvalidation.cacheInvalidationTopic

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object CacheInvalidator {

  val instanceId = "cache-invalidator"
  val whisksCollection = "whisks"

  def start(
    globalConfig: Config)(implicit system: ActorSystem, materializer: ActorMaterializer, log: Logging): Future[Done] = {
    implicit val ec: ExecutionContext = system.dispatcher
    val config = CacheInvalidatorConfig(globalConfig)
    val producer =
      KafkaEventProducer(
        kafkaProducerSettings(defaultProducerConfig(globalConfig)),
        cacheInvalidationTopic,
        config.eventProducerConfig)
    val observer = new WhiskChangeEventObserver(config.invalidatorConfig, producer)
    val feedConsumer = new ChangeFeedConsumer(whisksCollection, config, observer)
    feedConsumer.isStarted.andThen {
      case Success(_) =>
        registerShutdownTasks(system, feedConsumer, producer)
        log.info(this, s"Started the Cache invalidator service. ClusterId [${config.invalidatorConfig.clusterId}]")
      case Failure(t) =>
        log.error(this, "Error occurred while starting the Consumer" + Throwables.getStackTraceAsString(t))
    }
  }

  private def registerShutdownTasks(system: ActorSystem,
                                    feedConsumer: ChangeFeedConsumer,
                                    producer: KafkaEventProducer)(implicit ec: ExecutionContext, log: Logging): Unit = {
    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "closeFeedListeners") { () =>
      feedConsumer
        .close()
        .flatMap { _ =>
          producer.close().andThen {
            case Success(_) =>
              log.info(this, "Kafka producer successfully shutdown")
          }
        }
    }
  }

  def kafkaProducerSettings(config: Config): ProducerSettings[String, String] =
    ProducerSettings(config, new StringSerializer, new StringSerializer)

  def defaultProducerConfig(globalConfig: Config): Config = globalConfig.getConfig("akka.kafka.producer")

}

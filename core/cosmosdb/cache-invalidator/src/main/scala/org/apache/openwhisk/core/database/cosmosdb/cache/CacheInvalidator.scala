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
import com.typesafe.config.Config
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.core.database.RemoteCacheInvalidation.cacheInvalidationTopic

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Success

class CacheInvalidator(globalConfig: Config)(implicit system: ActorSystem, log: Logging) {
  import CacheInvalidator._
  val instanceId = "cache-invalidator"
  val whisksCollection = "whisks"
  implicit val ec: ExecutionContext = system.dispatcher

  val config = CacheInvalidatorConfig(globalConfig)
  val producer =
    KafkaEventProducer(
      kafkaProducerSettings(defaultProducerConfig(globalConfig)),
      cacheInvalidationTopic,
      config.eventProducerConfig)
  val observer = new WhiskChangeEventObserver(config.invalidatorConfig, producer)
  val feedConsumer: ChangeFeedConsumer = new ChangeFeedConsumer(whisksCollection, config, observer)
  val running = Promise[Done]

  def start(): (Future[Done], Future[Done]) = {
    //If there is a failure at feedConsumer.start, stop everything
    val startFuture = feedConsumer.start
    startFuture
      .map { _ =>
        registerShutdownTasks(system, feedConsumer, producer)
        log.info(this, s"Started the Cache invalidator service. ClusterId [${config.invalidatorConfig.clusterId}]")
      }
      .recover {
        case t: Throwable =>
          log.error(this, s"Shutdown after failure to start invalidator: ${t}")
          stop(Some(t))
      }

    //If the producer stream fails, stop everything.
    producer
      .getStreamFuture()
      .map(_ => log.info(this, "Successfully completed producer"))
      .recover {
        case t: Throwable =>
          log.error(this, s"Shutdown after producer failure: ${t}")
          stop(Some(t))
      }

    (startFuture, running.future)
  }
  def stop(error: Option[Throwable])(implicit system: ActorSystem, ec: ExecutionContext, log: Logging): Future[Done] = {
    feedConsumer
      .close()
      .andThen {
        case _ =>
          producer.close().andThen {
            case _ =>
              terminate(error)
          }
      }
  }
  def terminate(error: Option[Throwable]): Unit = {
    //make sure that the tracking future is only completed once, even though it may be called for various types of failures
    synchronized {
      if (!running.isCompleted) {
        error.map(running.failure).getOrElse(running.success(Done))
      }
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
}
object CacheInvalidator {
  def kafkaProducerSettings(config: Config): ProducerSettings[String, String] =
    ProducerSettings(config, new StringSerializer, new StringSerializer)

  def defaultProducerConfig(globalConfig: Config): Config = globalConfig.getConfig("akka.kafka.producer")

}

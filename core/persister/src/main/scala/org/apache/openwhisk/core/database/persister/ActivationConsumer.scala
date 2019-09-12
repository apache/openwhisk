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

package org.apache.openwhisk.core.database.persister

import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicReference

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.scaladsl.{Committer, Consumer}
import akka.kafka.{CommitterSettings, ConsumerSettings, Subscriptions}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{RestartSource, Sink}
import javax.management.ObjectName
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, StringDeserializer}
import org.apache.openwhisk.common.{Logging, LoggingMarkers, TransactionId}
import org.apache.openwhisk.core.connector.{AcknowledegmentMessage, ResultMessage}
import org.apache.openwhisk.core.entity.WhiskActivation
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

trait ActivationPersister {
  def persist(wa: WhiskActivation)(implicit tid: TransactionId): Future[Done]
}

case class RetryConfig(minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double, maxRestarts: Int)

class ActivationConsumer(config: PersisterConfig, persister: ActivationPersister)(implicit system: ActorSystem,
                                                                                  materializer: ActorMaterializer,
                                                                                  logging: Logging) {
  def isRunning: Boolean = !control.get().isShutdown.isCompleted

  private implicit val ec: ExecutionContext = system.dispatcher
  private implicit val tid: TransactionId = TransactionId(TransactionId.systemPrefix + "persister")

  private val server = ManagementFactory.getPlatformMBeanServer
  private val name = new ObjectName(s"kafka.consumer:type=consumer-fetch-manager-metrics,client-id=${config.clientId}")
  private val queueMetric = LoggingMarkers.KAFKA_QUEUE(config.topic)

  logging.info(this, "Starting the consumer with config " + config)

  private val control = new AtomicReference[Consumer.Control](Consumer.NoopControl)
  private val streamFuture: Future[Done] = {
    val committerDefaults = CommitterSettings(system)
    val r = config.retry
    val f = RestartSource
      .onFailuresWithBackoff(
        minBackoff = r.minBackoff,
        maxBackoff = r.maxBackoff,
        randomFactor = r.randomFactor,
        maxRestarts = r.maxRestarts) { () =>
        logging.info(this, "Starting the Kafka consumer source")
        Consumer
          .committableSource(consumerSettings(), createSubscription())
          .mapAsyncUnordered(config.parallelism) { msg =>
            val f = Try(parseActivation(msg.record.value())) match {
              case Success(Some((tid, a))) => persist(a)(tid)
              case Success(None)           => Future.successful(Done)
              case Failure(e) =>
                logging.warn(this, s"Error parsing json for record ${msg.record.key()}" + e)
                Future.successful(Done)
            }
            f.map(_ => msg.committableOffset)
          }
          .mapMaterializedValue(c => control.set(c))
          .via(Committer.flow(committerDefaults))
      }
      .runWith(Sink.ignore)

    f.failed.foreach(t => logging.error(this, "KafkaConsumer failed " + t.getMessage))
    f
  }

  private def createSubscription() = {
    if (config.topicIsPattern) {
      Subscriptions.topicPattern(config.topic)
    } else {
      Subscriptions.topics(config.topic)
    }
  }

  private val lagRecorder =
    system.scheduler.schedule(10.seconds, 10.seconds)(queueMetric.gauge.set(consumerLag))

  def shutdown(): Future[Done] = {
    lagRecorder.cancel()
    control.get().drainAndShutdown(streamFuture)(system.dispatcher)
  }

  def consumerLag: Long = server.getAttribute(name, "records-lag-max").asInstanceOf[Double].toLong.max(0)

  private def persist(act: WhiskActivation)(implicit tid: TransactionId): Future[Done] = {
    //TODO Failure case handling - If there is an issue in storing then stream
    persister.persist(act)
  }

  private def parseActivation(data: Array[Byte]): Option[(TransactionId, WhiskActivation)] = {
    //Avoid converting to string first and then to json. Instead directly parse to JSON
    val js = JsonParser(ParserInput(data))
    AcknowledegmentMessage.serdes.read(js) match {
      case ResultMessage(tid, Right(wa)) => Some((tid, wa))
      case _                             => None
    }
  }

  private def consumerSettings(): ConsumerSettings[String, Array[Byte]] =
    ConsumerSettings(system, new StringDeserializer, new ByteArrayDeserializer)
      .withGroupId(config.groupId)
      .withBootstrapServers(config.kafkaHosts)
      .withProperty(ConsumerConfig.CLIENT_ID_CONFIG, config.clientId)
}

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

package org.apache.openwhisk.connector.kafka

import akka.actor.ActorSystem
import akka.pattern.after
import org.apache.kafka.clients.producer._
import org.apache.kafka.common.errors._
import org.apache.kafka.common.serialization.StringSerializer
import pureconfig._
import pureconfig.generic.auto._
import org.apache.openwhisk.common.{Counter, Logging, TransactionId}
import org.apache.openwhisk.connector.kafka.KafkaConfiguration._
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.connector.{Message, MessageProducer}
import org.apache.openwhisk.core.entity.{ByteSize, UUIDs}
import org.apache.openwhisk.utils.Exceptions

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{blocking, ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

class KafkaProducerConnector(
  kafkahosts: String,
  id: String = UUIDs.randomUUID().toString,
  maxRequestSize: Option[ByteSize] = None)(implicit logging: Logging, actorSystem: ActorSystem)
    extends MessageProducer
    with Exceptions {

  implicit val ec: ExecutionContext = actorSystem.dispatcher
  private val gracefulWaitTime = 100.milliseconds

  override def sentCount(): Long = sentCounter.cur

  /** Sends msg to topic. This is an asynchronous operation. */
  override def send(topic: String, msg: Message, retry: Int = 3): Future[RecordMetadata] = {
    implicit val transid: TransactionId = msg.transid
    val record = new ProducerRecord[String, String](topic, "messages", msg.serialize)
    val produced = Promise[RecordMetadata]()

    Future {
      blocking {
        try {
          producer.send(record, new Callback {
            override def onCompletion(metadata: RecordMetadata, exception: Exception): Unit = {
              if (exception == null) produced.trySuccess(metadata)
              else produced.tryFailure(exception)
            }
          })
        } catch {
          case e: Throwable =>
            produced.tryFailure(e)
        }
      }
    }

    produced.future.andThen {
      case Success(status) =>
        logging.debug(this, s"sent message: ${status.topic()}[${status.partition()}][${status.offset()}]")
        sentCounter.next()
      case Failure(t) =>
        logging.error(this, s"sending message on topic '$topic' failed: ${t.getMessage}")
    } recoverWith {
      // Do not retry on these exceptions as they may cause duplicate messages on Kafka.
      case _: NotEnoughReplicasAfterAppendException | _: TimeoutException =>
        recreateProducer()
        produced.future
      case r: RetriableException if retry > 0 =>
        logging.info(this, s"$r: Retrying $retry more times")
        after(gracefulWaitTime, actorSystem.scheduler)(send(topic, msg, retry - 1))
      // Ignore this exception as restarting the producer doesn't make sense
      case e: RecordTooLargeException =>
        Future.failed(e)
      // All unknown errors just result in a recreation of the producer. The failure is propagated.
      case _: Throwable =>
        recreateProducer()
        produced.future
    }
  }

  /** Closes producer. */
  override def close(): Unit = {
    logging.info(this, "closing producer")
    producer.close()
  }

  private val sentCounter = new Counter()

  private def createProducer(): KafkaProducer[String, String] = {
    val config = Map(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG -> kafkahosts) ++
      configMapToKafkaConfig(loadConfigOrThrow[Map[String, String]](ConfigKeys.kafkaCommon)) ++
      configMapToKafkaConfig(loadConfigOrThrow[Map[String, String]](ConfigKeys.kafkaProducer)) ++
      (maxRequestSize map { max =>
        Map("max.request.size" -> max.size.toString)
      } getOrElse Map.empty)

    verifyConfig(config, ProducerConfig.configNames().asScala.toSet)

    tryAndThrow("creating producer")(new KafkaProducer(config, new StringSerializer, new StringSerializer))
  }

  private def recreateProducer(): Unit = {
    logging.info(this, s"recreating producer")
    tryAndSwallow("closing old producer")(producer.close())
    logging.info(this, s"old producer closed")
    producer = createProducer()
  }

  @volatile private var producer = createProducer()
}

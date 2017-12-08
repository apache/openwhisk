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
import akka.kafka.{ProducerMessage, ProducerSettings}
import akka.stream.scaladsl.{Sink, Source, SourceQueueWithComplete}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import org.apache.kafka.clients.producer.{ProducerRecord, RecordMetadata}
import org.apache.kafka.common.serialization.StringSerializer
import whisk.common.{Logging, TransactionId}
import whisk.core.connector.{Message, MessageProducer}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

class KafkaProducerConnector()(implicit actorSystem: ActorSystem, logging: Logging) extends MessageProducer {

  val producerSettings = ProducerSettings(actorSystem, new StringSerializer, new StringSerializer)

  private implicit val executionContext: ExecutionContext = actorSystem.dispatchers.lookup(producerSettings.dispatcher)
  private implicit val materializer: ActorMaterializer = ActorMaterializer()

  private val kafkaProducer: SourceQueueWithComplete[(ProducerRecord[String, String], Promise[RecordMetadata])] =
    Source
      .queue[(ProducerRecord[String, String], Promise[RecordMetadata])](Int.MaxValue, OverflowStrategy.dropNew)
      .map { case (msg, prom) => ProducerMessage.Message(msg, prom) }
      .via(OwKafkaProducer.flow(producerSettings))
      .map(result => result.message.passThrough.success(result.metadata))
      .to(Sink.ignore)
      .run()

  /** Sends msg to topic. This is an asynchronous operation. */
  override def send(topic: String, msg: Message, retry: Int = 2): Future[RecordMetadata] = {
    implicit val transid: TransactionId = msg.transid
    val record = new ProducerRecord[String, String](topic, "messages", msg.serialize)

    logging.debug(this, s"sending to topic '$topic' msg '$msg'")
    val produced = Promise[RecordMetadata]()

    // TODO handle all cases
    kafkaProducer.offer((record, produced))

    produced.future.andThen {
      case Success(status) =>
        logging.debug(this, s"sent message: ${status.topic()}[${status.partition()}][${status.offset()}]")
      case Failure(t) =>
        logging.error(this, s"sending message on topic '$topic' failed: ${t.getMessage}")
    }
  }

  /** Closes producer. */
  override def close(): Unit = kafkaProducer.complete()
}

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

import akka.NotUsed
import akka.kafka.ProducerMessage.{Message, Result}
import akka.kafka.ProducerSettings
import akka.stream.scaladsl.Flow
import org.apache.kafka.clients.producer.{Callback, Producer, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.errors.RetriableException

import scala.concurrent.{ExecutionContext, Future, Promise}

/**
 * Resilient Kafka producing flows. Modeled after the ideas in:
 *
 * - https://github.com/akka/reactive-kafka/issues/250
 * - https://github.com/akka/reactive-kafka/issues/344
 *
 * We (@markusthoemmes/@cbickel) plan to contribute those to akka-streams-kafka once hardened.
 */
object OwKafkaProducer {

  /**
   * Produces a message in kafka, retrying retriable exceptions.
   *
   * @param producer the KafkaProducer to use to produce the message
   * @param record the record to produce
   * @return a Future that completes with the RecordMetadata once the message is produced successfully
   */
  def produce[K, V](producer: Producer[K, V], record: ProducerRecord[K, V])(
    implicit ec: ExecutionContext): Future[RecordMetadata] = {
    val r = Promise[RecordMetadata]
    producer.send(
      record,
      new Callback {
        override def onCompletion(metadata: RecordMetadata, exception: Exception): Unit = {
          if (exception == null) {
            r.success(metadata)
          } else {
            r.failure(exception)
          }
        }
      })

    r.future.recoverWith {
      case e: RetriableException =>
        // TODO backoff strategy for the retry
        produce(producer, record)
      case t =>
        Future.failed(t)
    }
  }

  def flow[K, V, PassThrough](settings: ProducerSettings[K, V])(
    implicit ec: ExecutionContext): Flow[Message[K, V, PassThrough], Result[K, V, PassThrough], NotUsed] = {
    flow(settings, settings.createKafkaProducer())
  }

  def flow[K, V, PassThrough](settings: ProducerSettings[K, V], producer: Producer[K, V])(
    implicit ec: ExecutionContext): Flow[Message[K, V, PassThrough], Result[K, V, PassThrough], NotUsed] = {
    Flow[Message[K, V, PassThrough]]
      .mapAsync(settings.parallelism) { msg =>
        produce(producer, msg.record).map(meta => Result(meta, msg))
      }
  }
}

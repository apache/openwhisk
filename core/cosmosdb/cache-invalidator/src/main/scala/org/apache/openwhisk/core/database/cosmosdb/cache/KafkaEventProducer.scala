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
import akka.actor.ActorSystem
import akka.kafka.ProducerSettings
import akka.kafka.scaladsl.Producer
import akka.stream.scaladsl.{Keep, Source}
import akka.stream.{ActorMaterializer, OverflowStrategy, QueueOfferResult}
import org.apache.kafka.clients.producer.ProducerRecord

import scala.concurrent.Future

case class KafkaEventProducer(settings: ProducerSettings[String, String], topic: String)(
  implicit system: ActorSystem,
  materializer: ActorMaterializer) {
  private val bufferSize = 100

  private val queue = Source
    .queue(bufferSize, OverflowStrategy.dropNew) //TODO Use backpressure
    .toMat(Producer.plainSink(settings))(Keep.left)
    .run

  def send(msg: String): Future[QueueOfferResult] =
    queue.offer(new ProducerRecord[String, String](topic, "messages", msg))

  def close(): Future[Done] = {
    queue.complete()
    queue.watchCompletion()
  }
}

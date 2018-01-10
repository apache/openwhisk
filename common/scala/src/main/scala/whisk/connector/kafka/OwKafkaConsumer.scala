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

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.ConsumerMessage.CommittableOffsetBatch
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.scaladsl.{Keep, RestartSource, Source}
import akka.stream.{KillSwitches, UniqueKillSwitch}
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.errors.RetriableException
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, StringDeserializer}

import scala.collection.immutable.Queue
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

object OwKafkaConsumer {

  /**
   * Creates a source of messages read from kafka, deserialized as Strings.
   *
   * @param group groupId to use to connect to kafka
   * @param topic topic to subscribe to
   * @param maxBufferSize the maximum size of messages buffered
   * @return a source of messages from kafka
   */
  def bufferedSource(group: String, topic: String, maxBufferSize: Int)(
    implicit actorSystem: ActorSystem): Source[String, UniqueKillSwitch] = {

    batchedSouce(group, topic, maxBufferSize).mapConcat(identity)
  }

  /**
   * Creates a source of messages read from kafka in batches, deserialized as Strings.
   *
   * @param group groupId to use to connect to kafka
   * @param topic topic to subscribe to
   * @param maxBatchSize the maximum batch size to read
   * @return a source of batched messages from kafka
   */
  def batchedSouce(group: String, topic: String, maxBatchSize: Int)(
    implicit actorSystem: ActorSystem): Source[Queue[String], UniqueKillSwitch] = {
    val settings = ConsumerSettings(actorSystem, new ByteArrayDeserializer, new StringDeserializer)
      .withGroupId(group)
      .withProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxBatchSize.toString)

    implicit val ec: ExecutionContext = actorSystem.dispatchers.lookup(settings.dispatcher)

    RestartSource
      .withBackoff(minBackoff = 500.milliseconds, maxBackoff = 30.seconds, randomFactor = 0.1) { () =>
        Consumer
          .committableSource(settings, Subscriptions.topics(topic))
          .batch(maxBatchSize, Queue(_))(_ :+ _)
          .mapAsync(4) { msgs =>
            val batch =
              msgs.foldLeft(CommittableOffsetBatch.empty)((batch, msg) => batch.updated(msg.committableOffset))

            commitBatch(batch).map(_ => msgs.map(_.record.value))
          }
      }
      .viaMat(KillSwitches.single)(Keep.right)
  }

  /**
   * Commits a batch of messages and retries retriable exceptions.
   *
   * @param batch the batch to commit
   * @return a Future that completes once the batch has been committed
   */
  private def commitBatch(batch: CommittableOffsetBatch)(implicit ec: ExecutionContext): Future[Done] = {
    batch.commitScaladsl().recoverWith {
      case e: RetriableException =>
        // TODO backoff strategy for the retry
        commitBatch(batch)
    }
  }

}

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
import org.apache.kafka.clients.consumer.{ConsumerConfig, KafkaConsumer}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.{RetriableException, WakeupException}
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import pureconfig._
import pureconfig.generic.auto._
import org.apache.openwhisk.common.{Logging, LoggingMarkers, MetricEmitter, Scheduler}
import org.apache.openwhisk.connector.kafka.KafkaConfiguration._
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.connector.MessageConsumer
import org.apache.openwhisk.utils.Exceptions
import org.apache.openwhisk.utils.TimeHelpers._

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{blocking, ExecutionContext, Future}
import scala.util.Failure

case class KafkaConsumerConfig(sessionTimeoutMs: Long)

class KafkaConsumerConnector(
  kafkahost: String,
  groupid: String,
  topic: String,
  override val maxPeek: Int = Int.MaxValue)(implicit logging: Logging, actorSystem: ActorSystem)
    extends MessageConsumer
    with Exceptions {

  implicit val ec: ExecutionContext = actorSystem.dispatcher
  private val gracefulWaitTime = 100.milliseconds

  // The consumer is generally configured via getProps. This configuration only loads values necessary for "outer"
  // logic, like the wakeup timer.
  private val cfg = loadConfigOrThrow[KafkaConsumerConfig](ConfigKeys.kafkaConsumer)

  // Currently consumed offset, is used to calculate the topic lag.
  // It is updated from one thread in "peek", no concurrent data structure is necessary
  private var offset: Long = 0

  // Markers for metrics, initialized only once
  private val queueMetric = LoggingMarkers.KAFKA_QUEUE(topic)
  private val delayMetric = LoggingMarkers.KAFKA_MESSAGE_DELAY(topic)

  /**
   * Long poll for messages. Method returns once message are available but no later than given
   * duration.
   *
   * @param duration the maximum duration for the long poll
   */
  override def peek(duration: FiniteDuration = 500.milliseconds,
                    retry: Int = 3): Iterable[(String, Int, Long, Array[Byte])] = {

    // poll can be infinitely blocked in edge-cases, so we need to wakeup explicitly.
    val wakeUpTask = actorSystem.scheduler.scheduleOnce(cfg.sessionTimeoutMs.milliseconds + 1.second) {
      consumer.wakeup()
      logging.info(this, s"woke up consumer for topic '$topic'")
    }

    try {
      val response = synchronized(consumer.poll(duration)).asScala

      // Cancel the scheduled wake-up task immediately.
      wakeUpTask.cancel()

      val now = System.currentTimeMillis

      response.lastOption.foreach(record => offset = record.offset + 1)
      response.map { r =>
        // record the time between producing the message and reading it
        MetricEmitter.emitHistogramMetric(delayMetric, (now - r.timestamp).max(0))
        (r.topic, r.partition, r.offset, r.value)
      }
    } catch {
      case _: WakeupException if retry > 0 =>
        // Happens if the 'poll()' takes too long.
        // This exception should occur iff 'poll()' has been woken up by the scheduled task.
        // For this reason, it should not necessary to cancel the task. We cancel the task
        // to be on the safe side because an ineffective `wakeup()` applies to the next
        // consumer call that can be woken up.
        // The scheduler is expected to safely ignore the cancellation of a task that already
        // has been cancelled or is already complete.
        wakeUpTask.cancel()
        logging.error(this, s"poll timeout occurred. Retrying $retry more times.")
        Thread.sleep(gracefulWaitTime.toMillis) // using Thread.sleep is okay, since `poll` is blocking anyway
        peek(duration, retry - 1)
      case e: RetriableException if retry > 0 =>
        // Happens if something goes wrong with 'poll()' and 'poll()' can be retried.
        wakeUpTask.cancel()
        logging.error(this, s"poll returned with failure. Retrying $retry more times. Exception: $e")
        Thread.sleep(gracefulWaitTime.toMillis) // using Thread.sleep is okay, since `poll` is blocking anyway
        peek(duration, retry - 1)
      case e: Throwable =>
        // Every other error results in a restart of the consumer
        wakeUpTask.cancel()
        logging.error(this, s"poll returned with failure. Recreating the consumer. Exception: $e")
        recreateConsumer()
        throw e
    }
  }

  /**
   * Commits offsets from last poll.
   */
  def commit(retry: Int = 3): Unit =
    try {
      synchronized(consumer.commitSync())
    } catch {
      case e: RetriableException =>
        if (retry > 0) {
          logging.error(this, s"$e: retrying $retry more times")
          Thread.sleep(gracefulWaitTime.toMillis) // using Thread.sleep is okay, since `commitSync` is blocking anyway
          commit(retry - 1)
        } else {
          throw e
        }
      case e: WakeupException =>
        logging.info(this, s"WakeupException happened when do commit action for topic ${topic}")
        recreateConsumer()
    }

  override def close(): Unit = synchronized {
    logging.info(this, s"closing consumer for '$topic'")
    consumer.close()
  }

  /** Creates a new kafka consumer and subscribes to topic list if given. */
  private def createConsumer(topic: String) = {
    val config = Map(
      ConsumerConfig.CLIENT_ID_CONFIG -> s"consumer-$topic",
      ConsumerConfig.GROUP_ID_CONFIG -> groupid,
      ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> kafkahost,
      ConsumerConfig.MAX_POLL_RECORDS_CONFIG -> maxPeek.toString) ++
      configMapToKafkaConfig(loadConfigOrThrow[Map[String, String]](ConfigKeys.kafkaCommon)) ++
      configMapToKafkaConfig(loadConfigOrThrow[Map[String, String]](ConfigKeys.kafkaConsumer))

    verifyConfig(config, ConsumerConfig.configNames().asScala.toSet)

    val consumer = tryAndThrow(s"creating consumer for $topic") {
      new KafkaConsumer(config, new ByteArrayDeserializer, new ByteArrayDeserializer)
    }

    // subscribe does not need to be synchronized, because the reference to the consumer hasn't been returned yet and
    // thus this is guaranteed only to be called by the calling thread.
    tryAndThrow(s"subscribing to $topic")(consumer.subscribe(Seq(topic).asJavaCollection))

    consumer
  }

  private def recreateConsumer(): Unit = synchronized {
    logging.info(this, s"recreating consumer for '$topic'")
    // According to documentation, the consumer is force closed if it cannot be closed gracefully.
    // See https://kafka.apache.org/11/javadoc/index.html?org/apache/kafka/clients/consumer/KafkaConsumer.html
    //
    // For the moment, we have no special handling of 'InterruptException' - it may be possible or even
    // needed to re-try the 'close()' when being interrupted.
    tryAndSwallow("closing old consumer")(consumer.close())
    logging.info(this, s"old consumer closed for '$topic'")

    consumer = createConsumer(topic)
  }

  @volatile private var consumer: KafkaConsumer[Array[Byte], Array[Byte]] = createConsumer(topic)

  // Read current lag of the consumed topic, e.g. invoker queue
  // Since we use only one partition in kafka, it is defined 0
  Scheduler.scheduleWaitAtMost(
    interval = loadConfigOrThrow[KafkaConfig](ConfigKeys.kafka).consumerLagCheckInterval,
    initialDelay = 10.seconds,
    name = "kafka-lag-monitor") { () =>
    Future {
      blocking {
        if (offset > 0) {
          val topicAndPartition = new TopicPartition(topic, 0)
          synchronized(consumer.endOffsets(Set(topicAndPartition).asJava)).asScala.get(topicAndPartition).foreach {
            endOffset =>
              // endOffset could lag behind the offset reported by the consumer internally resulting in negative numbers
              val queueSize = (endOffset - offset).max(0)
              MetricEmitter.emitGaugeMetric(queueMetric, queueSize)
          }
        }
      }
    }.andThen {
      case Failure(_: WakeupException) =>
        recreateConsumer()
      case Failure(e) =>
        // Only log level info because failed metric reporting is not critical
        logging.info(this, s"lag metric reporting failed for topic '$topic': $e")
    }
  }
}

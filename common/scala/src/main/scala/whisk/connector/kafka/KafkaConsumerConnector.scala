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
import org.apache.kafka.clients.consumer.{ConsumerConfig, KafkaConsumer}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.{RetriableException, WakeupException}
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import pureconfig.loadConfigOrThrow
import whisk.common.{Logging, LoggingMarkers, MetricEmitter, Scheduler}
import whisk.connector.kafka.KafkaConfiguration._
import whisk.core.ConfigKeys
import whisk.core.connector.MessageConsumer

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{blocking, ExecutionContext, Future}

case class KafkaConsumerConfig(sessionTimeoutMs: Long, metricFlushIntervalS: Int)

class KafkaConsumerConnector(
  kafkahost: String,
  groupid: String,
  topic: String,
  override val maxPeek: Int = Int.MaxValue)(implicit logging: Logging, actorSystem: ActorSystem)
    extends MessageConsumer {

  implicit val ec: ExecutionContext = actorSystem.dispatcher
  private val gracefulWaitTime = 100.milliseconds

  // The consumer is generally configured via getProps. This configuration only loads values necessary for "outer"
  // logic, like the wakeup timer.
  private val cfg = loadConfigOrThrow[KafkaConsumerConfig](ConfigKeys.kafkaConsumer)

  // Currently consumed offset, is used to calculate the topic lag.
  // It is updated from one thread in "peek", no concurrent data structure is necessary
  private var offset: Long = 0

  /**
   * Long poll for messages. Method returns once message are available but no later than given
   * duration.
   *
   * @param duration the maximum duration for the long poll
   */
  override def peek(duration: FiniteDuration = 500.milliseconds,
                    retry: Int = 3): Iterable[(String, Int, Long, Array[Byte])] = {

    // poll can be infinitely blocked in edge-cases, so we need to wakeup explicitly.
    val wakeUpTask = actorSystem.scheduler.scheduleOnce(cfg.sessionTimeoutMs.milliseconds + 1.second)(consumer.wakeup())

    try {
      val response = consumer.poll(duration.toMillis).asScala.map(r => (r.topic, r.partition, r.offset, r.value))
      response.lastOption.foreach {
        case (_, _, newOffset, _) => offset = newOffset + 1
      }
      response
    } catch {
      // Happens if the peek hangs.
      case _: WakeupException if retry > 0 =>
        logging.error(this, s"poll timeout occurred. Retrying $retry more times.")
        Thread.sleep(gracefulWaitTime.toMillis) // using Thread.sleep is okay, since `poll` is blocking anyway
        peek(duration, retry - 1)
      case e: RetriableException if retry > 0 =>
        logging.error(this, s"$e: Retrying $retry more times")
        wakeUpTask.cancel()
        Thread.sleep(gracefulWaitTime.toMillis) // using Thread.sleep is okay, since `poll` is blocking anyway
        peek(duration, retry - 1)
      // Every other error results in a restart of the consumer
      case e: Throwable =>
        recreateConsumer()
        throw e
    } finally wakeUpTask.cancel()
  }

  /**
   * Commits offsets from last poll.
   */
  def commit(retry: Int = 3): Unit =
    try {
      consumer.commitSync()
    } catch {
      case e: RetriableException =>
        if (retry > 0) {
          logging.error(this, s"$e: retrying $retry more times")
          Thread.sleep(gracefulWaitTime.toMillis) // using Thread.sleep is okay, since `commitSync` is blocking anyway
          commit(retry - 1)
        } else {
          throw e
        }
    }

  override def close(): Unit = {
    consumer.close()
    logging.info(this, s"closing '$topic' consumer")
  }

  /** Creates a new kafka consumer and subscribes to topic list if given. */
  private def createConsumer(topic: String) = {
    val config = Map(
      ConsumerConfig.GROUP_ID_CONFIG -> groupid,
      ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> kafkahost,
      ConsumerConfig.MAX_POLL_RECORDS_CONFIG -> maxPeek.toString) ++
      configMapToKafkaConfig(loadConfigOrThrow[Map[String, String]](ConfigKeys.kafkaCommon)) ++
      configMapToKafkaConfig(loadConfigOrThrow[Map[String, String]](ConfigKeys.kafkaConsumer))

    val consumer = new KafkaConsumer(config, new ByteArrayDeserializer, new ByteArrayDeserializer)
    consumer.subscribe(Seq(topic).asJavaCollection)
    consumer
  }

  private def recreateConsumer(): Unit = {
    val oldConsumer = consumer
    oldConsumer.close()
    logging.info(this, s"old consumer closed")
    consumer = createConsumer(topic)
  }

  @volatile private var consumer: KafkaConsumer[Array[Byte], Array[Byte]] = createConsumer(topic)

  // Read current lag of the consumed topic, e.g. invoker queue
  // Since we use only one partition in kafka, it is defined 0
  Scheduler.scheduleWaitAtMost(cfg.metricFlushIntervalS.seconds, 10.seconds, "kafka-lag-monitor") { () =>
    Future {
      blocking {
        val topicAndPartition = new TopicPartition(topic, 0)
        consumer.endOffsets(Set(topicAndPartition).asJava).asScala.get(topicAndPartition).foreach { endOffset =>
          // endOffset could lag behind the offset reported by the consumer internally resulting in negative numbers
          val queueSize = (endOffset - offset).max(0)
          MetricEmitter.emitHistogramMetric(LoggingMarkers.KAFKA_QUEUE(topic), queueSize)
        }
      }
    }
  }
}

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

package whisk.core.connector.test

import java.util.ArrayList
import java.util.concurrent.LinkedBlockingQueue
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.collection.JavaConversions._
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.record.Record
import common.StreamLogging
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import whisk.common.Counter
import whisk.common.Logging
import whisk.core.WhiskConfig
import whisk.core.connector.Message
import whisk.core.connector.MessageConsumer
import whisk.core.connector.MessageProducer
import whisk.core.connector.MessagingProvider
import whisk.core.entity.InstanceId

class TestConnector(topic: String, override val maxPeek: Int, allowMoreThanMax: Boolean)
    extends MessageConsumer
    with StreamLogging {

  override def peek(duration: Duration) = {
    val msgs = new ArrayList[Message]
    queue.synchronized {
      queue.drainTo(msgs, if (allowMoreThanMax) Int.MaxValue else maxPeek)
      msgs map { m =>
        offset += 1
        (topic, -1, offset, m.serialize.getBytes)
      }
    }
  }

  override def commit() = {
    if (throwCommitException) {
      throw new Exception("commit failed")
    } else {
      // nothing to do
    }
  }

  def occupancy = queue.size

  def send(msg: Message): Future[RecordMetadata] = {
    producer.send(topic, msg)
  }

  def send(msgs: Seq[Message]): Future[RecordMetadata] = {
    import scala.language.reflectiveCalls
    producer.sendBulk(topic, msgs)
  }

  def close() = {
    closed = true
    producer.close()
  }

  private val producer = new MessageProducer {
    def send(topic: String, msg: Message): Future[RecordMetadata] = {
      queue.synchronized {
        if (queue.offer(msg)) {
          logging.info(this, s"put: $msg")
          Future.successful(
            new RecordMetadata(new TopicPartition(topic, 0), 0, queue.size, Record.NO_TIMESTAMP, -1, -1, -1))
        } else {
          logging.error(this, s"put failed: $msg")
          Future.failed(new IllegalStateException("failed to write msg"))
        }
      }
    }

    def sendBulk(topic: String, msgs: Seq[Message]): Future[RecordMetadata] = {
      queue.synchronized {
        if (queue.addAll(msgs)) {
          logging.info(this, s"put: ${msgs.length} messages")
          Future.successful(
            new RecordMetadata(new TopicPartition(topic, 0), 0, queue.size, Record.NO_TIMESTAMP, -1, -1, -1))
        } else {
          logging.error(this, s"put failed: ${msgs.length} messages")
          Future.failed(new IllegalStateException("failed to write msg"))
        }
      }
    }

    def close() = {}
    def sentCount() = counter.next()
    val counter = new Counter()
  }

  var throwCommitException = false
  private val queue = new LinkedBlockingQueue[Message]()
  @volatile private var closed = false
  private var offset = -1L
}

object TestMessagingProvider extends MessagingProvider {
  val queues = mutable.Map[String, LinkedBlockingQueue[Message]]()

  val instanceIdMap = mutable.Map[TestConsumer, InstanceId]()
  override def getConsumer(config: WhiskConfig,
                           groupId: String,
                           topic: String,
                           maxPeek: Int,
                           maxPollInterval: FiniteDuration)(implicit logging: Logging) = {
    this.synchronized {

      val queue = queues.getOrElseUpdate(topic, {
        new LinkedBlockingQueue[Message]()
      })
      new TestConsumer(queue, topic, maxPeek, false)
    }

  }

  override def getProducer(config: WhiskConfig, ec: ExecutionContext)(implicit logging: Logging) =
    //connector
    new MessageProducer with StreamLogging {
      def send(topic: String, msg: Message): Future[RecordMetadata] = {
        val queue = queues.getOrElseUpdate(topic, {
          new LinkedBlockingQueue[Message]()
        })
        queue.synchronized {
          if (queue.offer(msg)) {
            logging.info(this, s"put: $msg")
            Future.successful(
              new RecordMetadata(new TopicPartition(topic, 0), 0, queue.size, Record.NO_TIMESTAMP, -1, -1, -1))
          } else {
            logging.error(this, s"put failed: $msg")
            Future.failed(new IllegalStateException("failed to write msg"))
          }
        }
      }

      def sendBulk(topic: String, msgs: Seq[Message]): Future[RecordMetadata] = {
        val queue = queues.getOrElseUpdate(topic, new LinkedBlockingQueue[Message]())
        queue.synchronized {
          if (queue.addAll(msgs)) {
            logging.info(this, s"put: ${msgs.length} messages")
            Future.successful(
              new RecordMetadata(new TopicPartition(topic, 0), 0, queue.size, Record.NO_TIMESTAMP, -1, -1, -1))
          } else {
            logging.error(this, s"put failed: ${msgs.length} messages")
            Future.failed(new IllegalStateException("failed to write msg"))
          }
        }
      }

      def close() = {}
      def sentCount() = counter.next()
      val counter = new Counter()
    }

  def occupancy(topic: String) = {

    queues(topic).size()

  }
}

class TestConsumer(queue: LinkedBlockingQueue[Message], topic: String, val maxPeek: Int, allowMoreThanMax: Boolean)
    extends MessageConsumer
    with StreamLogging {
  var throwCommitException = false
  @volatile var dontPeek: Boolean = false
  @volatile private var closed = false
  var offset = 0l

  /**
   * Gets messages via a long poll. May or may not remove messages
   * from the message connector. Use commit() to ensure messages are
   * removed from the connector.
   *
   * @param duration for the long poll
   * @return iterable collection (topic, partition, offset, bytes)
   */
  override def peek(duration: Duration): Iterable[(String, Int, Long, Array[Byte])] = {
    require(closed == false, "cannot operate on a closed consumer")
    val res = if (dontPeek) {
      List.empty
    } else {
      queue.synchronized {
        val msgs = new ArrayList[Message]
        queue.drainTo(msgs, if (allowMoreThanMax) Int.MaxValue else maxPeek)
        val res = msgs map { m =>
          offset += 1
          (topic, -1, offset, m.serialize.getBytes)
        }
        res
      }
    }
    val sleepTime: Long = duration.toMillis
    Thread.sleep(sleepTime)
    res
  }

  /**
   * Commits offsets from last peek operation to ensure they are removed
   * from the connector.
   */
  override def commit(): Unit = {
    if (throwCommitException) {
      throw new Exception("commit failed")
    } else {
      // nothing to do
    }
  }

  /** Closes consumer. */
  override def close(): Unit = this.closed = true

  def occupancy: Int = queue.size()
}

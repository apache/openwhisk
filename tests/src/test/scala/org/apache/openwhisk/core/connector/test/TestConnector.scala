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

package org.apache.openwhisk.core.connector.test

import java.util.ArrayList
import java.util.concurrent.LinkedBlockingQueue
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.collection.JavaConverters._
import common.StreamLogging
import org.apache.openwhisk.common.Counter
import org.apache.openwhisk.core.connector.{Message, MessageConsumer, MessageProducer, ResultMetadata}

class TestConnector(topic: String, override val maxPeek: Int, allowMoreThanMax: Boolean)
    extends MessageConsumer
    with StreamLogging {

  override def peek(duration: FiniteDuration, retry: Int = 0) = {
    val msgs = new ArrayList[Message]
    queue.synchronized {
      queue.drainTo(msgs, if (allowMoreThanMax) Int.MaxValue else maxPeek)
      msgs.asScala map { m =>
        offset += 1
        (topic, -1, offset, m.serialize.getBytes)
      }
    }
  }

  override def commit(retry: Int = 0) = {
    if (throwCommitException) {
      throw new Exception("commit failed")
    } else {
      // nothing to do
    }
  }

  def occupancy = queue.size

  def send(msg: Message): Future[ResultMetadata] = {
    producer.send(topic, msg)
  }

  def send(msgs: Seq[Message]): Future[ResultMetadata] = {
    import scala.language.reflectiveCalls
    producer.sendBulk(topic, msgs)
  }

  def close() = {
    closed = true
    producer.close()
  }

  def getProducer(): MessageProducer = producer

  private val producer = new MessageProducer {
    def send(topic: String, msg: Message, retry: Int = 0): Future[ResultMetadata] = {
      queue.synchronized {
        if (queue.offer(msg)) {
          logging.info(this, s"put: $msg")
          Future.successful(ResultMetadata(topic, 0, queue.size()))
        } else {
          logging.error(this, s"put failed: $msg")
          Future.failed(new IllegalStateException("failed to write msg"))
        }
      }
    }

    def sendBulk(topic: String, msgs: Seq[Message]): Future[ResultMetadata] = {
      queue.synchronized {
        if (queue.addAll(msgs.asJava)) {
          logging.info(this, s"put: ${msgs.length} messages")
          Future.successful(ResultMetadata(topic, 0, queue.size()))
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

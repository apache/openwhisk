/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.dispatcher.test

import java.util.ArrayList
import java.util.concurrent.LinkedBlockingQueue

import scala.collection.JavaConversions.asScalaBuffer
import scala.concurrent.Future
import scala.concurrent.duration.Duration

import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.record.Record

import whisk.common.Counter
import whisk.common.Logging
import whisk.core.connector.Message
import whisk.core.connector.MessageConsumer
import whisk.core.connector.MessageProducer

class TestConnector(
    topic: String,
    override val maxPeek: Int,
    allowMoreThanMax: Boolean)
    extends MessageConsumer
    with Logging {

    override def peek(duration: Duration) = {
        val msgs = new ArrayList[Message]
        queue.drainTo(msgs, if (allowMoreThanMax) Int.MaxValue else maxPeek)

        msgs map { m =>
            offset += 1
            (topic, -1, offset, m.serialize.getBytes)
        }
    }

    override def commit() = {
        // nothing to do
    }

    override def onMessage(process: (String, Int, Long, Array[Byte]) => Unit): Unit = {
        new Thread {
            override def run() = while (!closed) {
                val msg = queue.take()
                info(this, s"received message for '$topic'")
                process(topic, -1, -1, msg.serialize.getBytes)
                Thread.sleep(100) // let producer get in a send if any
            }
        }.start
    }

    def occupancy = queue.size

    def send(msg: Message): Future[RecordMetadata] = {
        producer.send(topic, msg)
    }

    def close() = {
        closed = true
        producer.close()
    }

    private val producer = new MessageProducer {
        def send(topic: String, msg: Message): Future[RecordMetadata] = {
            if (queue.offer(msg)) {
                info(this, s"put: $msg")
                Future.successful(new RecordMetadata(new TopicPartition(topic, 0), 0, queue.size, Record.NO_TIMESTAMP, -1, -1, -1))
            } else {
                error(this, s"put failed: $msg")
                Future.failed(new IllegalStateException("failed to write msg"))
            }
        }
        def close() = {}
        def sentCount() = counter.next()
        val counter = new Counter()
    }

    private val queue = new LinkedBlockingQueue[Message]()
    @volatile private var closed = false
    private var offset = -1L
}

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

import java.util.concurrent.LinkedBlockingQueue

import scala.concurrent.Future

import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition

import whisk.common.Counter
import whisk.core.connector.Message
import whisk.core.connector.MessageConsumer
import whisk.core.connector.MessageProducer
import whisk.core.dispatcher.MessageDispatcher

class TestDispatcher(topic: String)
    extends MessageDispatcher
    with MessageConsumer {

    def send(msg: Message): Future[RecordMetadata] = {
        producer.send(topic, msg)
    }

    def onMessage(process: Array[Byte] => Boolean): Unit = {
        new Thread {
            override def run() = while (!closed) {
                val msg = queue.take()
                println(s"received message for '$topic' ${new String(msg, "utf-8")}")
                process(msg)
            }
        }.start
    }

    def close() = {
        closed = true
        producer.close()
    }

    private val producer = new MessageProducer {
        def send(topic: String, msg: Message): Future[RecordMetadata] = {
            if (queue.offer(msg.serialize.getBytes)) {
                println(s"put: $msg")
                Future.successful(new RecordMetadata(new TopicPartition(topic, 0), 0, queue.size))
            } else {
                println(s"put failed: $msg")
                Future.failed(new IllegalStateException("failed to write msg"))
            }
        }
        def close() = {}
        def sentCount() = counter.next()
        val counter = new Counter()
    }

    private val queue = new LinkedBlockingQueue[Array[Byte]]()
    @volatile private var closed = false
}

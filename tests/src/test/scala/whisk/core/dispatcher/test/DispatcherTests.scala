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

package whisk.core.dispatcher.test

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import akka.actor.actorRef2Scala
import common.StreamLogging
import common.WskActorSystem
import spray.json.JsNumber
import spray.json.JsObject
import whisk.common.TransactionId
import whisk.core.connector.{ ActivationMessage => Message }
import whisk.core.connector.MessageFeed
import whisk.core.connector.test.TestConnector
import whisk.core.controller.test.WhiskAuthHelpers
import whisk.core.dispatcher.Dispatcher
import whisk.core.dispatcher.MessageHandler
import whisk.core.entity._
import whisk.utils.retry

@RunWith(classOf[JUnitRunner])
class DispatcherTests
    extends FlatSpec
    with Matchers
    with WskActorSystem
    with StreamLogging {

    implicit val transid = TransactionId.testing

    behavior of "Dispatcher"

    def logContains(w: String)(implicit stream: java.io.ByteArrayOutputStream): Boolean = {
        retry({
            val log = stream.toString()
            val result = log.contains(w)
            assert(result) // throws exception required to retry
            result
        }, 10, Some(100 milliseconds))
    }

    def sendMessage(connector: TestConnector, count: Int) = {
        val content = JsObject("payload" -> JsNumber(count))
        val user = WhiskAuthHelpers.newIdentity()
        val path = FullyQualifiedEntityName(EntityPath("test"), EntityName(s"count-$count"), Some(SemVer()))
        val msg = Message(TransactionId.testing, path, DocRevision.empty, user, ActivationId(), EntityPath(user.subject.asString), InstanceId(0), Some(content))
        connector.send(msg)
    }

    class TestRule(dosomething: Message => Any) extends MessageHandler("test message handler") {
        override def onMessage(msg: Message)(implicit transid: TransactionId): Future[Any] = {
            logging.debug(this, s"received: ${msg.content.get.compactPrint}")
            Future.successful {
                dosomething(msg)
            }
        }
    }

    it should "send and receive a message from connector bus" in {
        val capacity = 4
        val connector = new TestConnector("test connector", capacity, false)

        val messagesProcessed = new AtomicInteger()
        val handler = new TestRule({ msg => messagesProcessed.incrementAndGet() })
        val dispatcher = new Dispatcher(connector, 100 milliseconds, capacity, actorSystem)
        dispatcher.addHandler(handler, true)
        dispatcher.start()

        try {
            withClue("commit exception must be caught") {
                connector.throwCommitException = true
                Console.withErr(stream) {
                    retry({
                        val logs = stream.toString()
                        logs should include regex (s"exception while pulling new activation records *.* commit failed")
                    }, 10, Some(100 milliseconds))

                    connector.throwCommitException = false
                }
            }

            for (i <- 0 until (2 * capacity + 1)) {
                sendMessage(connector, i + 1)
            }

            // only process as many messages as we have downstream capacity
            withClue("messages processed") {
                retry({
                    messagesProcessed.get should be(capacity)
                }, 20, Some(100 milliseconds))
            }

            withClue("confirming dispatcher is in overflow state") {
                val logs = stream.toString()
                logs should include regex (s"activation pipeline must drain: ${capacity + 1} > $capacity")
            }

            // send one message and check later that it remains in the connector
            // at this point, total messages sent = 2 * capacity + 2
            connector.occupancy shouldBe 0
            sendMessage(connector, 2 * capacity + 2)
            Thread.sleep(1.second.toMillis)

            withClue("expecting message to still be in the queue") {
                retry({
                    connector.occupancy shouldBe 1
                }, 10, Some(100 milliseconds))
            }

            // unblock the pipeline by draining 1 activations and check
            // that dispatcher refilled the pipeline
            stream.reset()
            Console.withOut(stream) {
                dispatcher.activationFeed ! MessageFeed.Processed
                // wait until additional message is drained
                retry({
                    withClue("additional messages processed") {
                        messagesProcessed.get shouldBe capacity + 1
                    }
                }, 10, Some(100 milliseconds))
            }

            withClue("confirm dispatcher tried to fill the pipeline") {
                val logs = stream.toString()
                logs should include regex (s"activation pipeline has capacity: $capacity <= $capacity")
            }
        } finally {
            dispatcher.stop()
        }
    }
}

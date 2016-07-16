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

import java.io.PrintStream
import java.util.Calendar

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.matching.Regex.Match

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfter
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Finders
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import akka.actor.ActorSystem
import akka.actor.actorRef2Scala
import spray.json.JsObject
import spray.json.JsString
import whisk.common.TransactionId
import whisk.common.Verbosity
import whisk.core.connector.{ ActivationMessage => Message }
import whisk.core.dispatcher.ActivationFeed
import whisk.core.dispatcher.DispatchRule
import whisk.core.dispatcher.Dispatcher
import whisk.core.entity.ActivationId
import whisk.core.entity.Subject
import whisk.utils.retry
import java.util.concurrent.atomic.AtomicInteger

@RunWith(classOf[JUnitRunner])
class DispatcherTests extends FlatSpec with Matchers with BeforeAndAfter with BeforeAndAfterAll {
    implicit val transid = TransactionId.testing
    implicit val actorSystem = ActorSystem("dispatchertests")

    override def afterAll() {
        println("Shutting down actor system")
        actorSystem.terminate()
        Await.result(actorSystem.whenTerminated, Duration.Inf)
    }

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
        val now = Calendar.getInstance().getTime().toString
        val content = JsObject("payload" -> JsString(now))
        val msg = Message(TransactionId.testing, s"/test/$count", Subject(), ActivationId(), Some(content))
        connector.send(msg)
    }

    class TestRule(dosomething: Message => Any) extends DispatchRule("test message handler", "/test", ".+") {
        setVerbosity(Verbosity.Loud)
        override def doit(topic: String, msg: Message, matches: Seq[Match])(implicit transid: TransactionId): Future[Any] = {
            info(this, s"received: ${msg.content.get.compactPrint}")
            Future.successful {
                dosomething(msg)
            }
        }
    }

    it should "send and receive a message from connector bus" in {
        val maxdepth = 8
        val half = maxdepth / 2
        val connector = new TestConnector("test connector", maxdepth / 2, true)
        val messagesProcessed = new AtomicInteger()
        val handler = new TestRule({ msg => messagesProcessed.incrementAndGet() })
        val dispatcher = new Dispatcher(Verbosity.Debug, connector, 100 milliseconds, maxdepth, actorSystem)
        dispatcher.addHandler(handler, true)
        dispatcher.start()

        implicit val stream = new java.io.ByteArrayOutputStream
        try {
            dispatcher.outputStream = new PrintStream(stream)
            Console.withOut(stream) {
                for (i <- 0 until half + 1) {
                    sendMessage(connector, i)
                }

                // wait until all messages are received at which point the
                // dispatcher cannot drain anymore messages
                withClue("the queue should be empty since all messages are drained") {
                    retry({
                        connector.occupancy shouldBe 0
                    }, 10, Some(100 milliseconds))
                }

                withClue("messages processed") {
                    retry({
                        messagesProcessed.get should be(half + 1)
                    }, 20, Some(100 milliseconds))
                }
            }

            withClue("confirming dispatcher is in overflow state") {
                val logs = stream.toString()
                logs should include regex (s"waiting for activation pipeline to drain: ${half + 1} > $half")
            }

            withClue("send more messages and confirm none are drained") {
                sendMessage(connector, half + 2)
                retry({
                    connector.occupancy shouldBe 1
                }, 10, Some(100 milliseconds))
            }

            withClue("confirming dispatcher did not consume additional messages when in overflow state") {
                stream.reset()
                Console.withOut(stream) {
                    dispatcher.activationFeed ! ActivationFeed.FillQueueWithMessages
                    retry({
                        val logs = stream.toString()
                        logs should include regex (s"dropping fill request until feed is drained")
                        logs should not include regex(s"waiting for activation pipeline to drain: ${messagesProcessed.get} > $half")
                    }, 10, Some(100 milliseconds))
                }
            }

            // unblock the pipeline by draining 1 activations and check
            // that dispatcher refilled the pipeline
            stream.reset()
            Console.withOut(stream) {
                dispatcher.activationFeed ! ActivationFeed.ContainerReleased(transid)
                // wait until additional message is drained
                retry({
                    withClue("additional messages processed") {
                        messagesProcessed.get shouldBe half + 2
                    }
                }, 10, Some(100 milliseconds))
            }

            withClue("confirm dispatcher is trying to fill the pipeline") {
                val logs = stream.toString()
                logs should include regex (s"filling activation pipeline: $half <= $half")
            }
        } finally {
            dispatcher.stop()
            stream.close()
            dispatcher.outputStream.close()
        }
    }
}

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
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import akka.actor.actorRef2Scala
import akka.event.Logging.{ InfoLevel, DebugLevel }
import common.WskActorSystem
import spray.json.JsNumber
import spray.json.JsObject
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.connector.{ ActivationMessage => Message }
import whisk.core.dispatcher.ActivationFeed
import whisk.core.dispatcher.Dispatcher
import whisk.core.dispatcher.MessageHandler
import whisk.core.entity.ActivationId
import whisk.core.entity.AuthKey
import whisk.core.entity.DocRevision
import whisk.core.entity.EntityName
import whisk.core.entity.EntityPath
import whisk.core.entity.FullyQualifiedEntityName
import whisk.core.entity.SemVer
import whisk.core.entity.Subject
import whisk.utils.retry

@RunWith(classOf[JUnitRunner])
class DispatcherTests extends FlatSpec with Matchers with WskActorSystem {
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
        val subject = Subject()
        val authkey = AuthKey()
        val path = FullyQualifiedEntityName(EntityPath("test"), EntityName(s"count-$count"), Some(SemVer()))
        val msg = Message(TransactionId.testing, path, DocRevision(), subject, authkey, ActivationId(), EntityPath(subject()), Some(content))
        connector.send(msg)
    }

    class TestRule(dosomething: Message => Any) extends MessageHandler("test message handler") with Logging {
        setVerbosity(InfoLevel)
        override def onMessage(msg: Message)(implicit transid: TransactionId): Future[Any] = {
            debug(this, s"received: ${msg.content.get.compactPrint}")
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
        val dispatcher = new Dispatcher(DebugLevel, connector, 100 milliseconds, maxdepth, actorSystem)
        dispatcher.addHandler(handler, true)
        dispatcher.start()

        implicit val stream = new java.io.ByteArrayOutputStream
        dispatcher.outputStream = new PrintStream(stream)

        try {
            withClue("commit exception must be caught") {
                connector.throwCommitException = true
                Console.withErr(stream) {
                    retry({
                        val logs = stream.toString()
                        logs should include regex (s"exception while pulling new records *.* commit failed")
                    }, 10, Some(100 milliseconds))

                    connector.throwCommitException = false
                }
            }

            for (i <- 0 to half) {
                sendMessage(connector, i + 1)
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

            withClue("confirming dispatcher is in overflow state") {
                val logs = stream.toString()
                logs should include regex (s"waiting for activation pipeline to drain: ${half + 1} > $half")
            }

            // send one message and check later that it remains in the connector
            // at this point, total messages sent = half + 2
            sendMessage(connector, half + 2)

            withClue("confirming dispatcher will not consume additional messages when in overflow state") {
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

            withClue("expecting message to still be in the queue") {
                connector.occupancy shouldBe 1
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

            withClue("confirm dispatcher tried to fill the pipeline") {
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

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

import java.util.Calendar

import scala.concurrent.Future

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfter
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import spray.http.HttpRequest
import spray.json.JsObject
import spray.json.JsString
import whisk.common.TransactionId
import whisk.common.Verbosity
import whisk.core.WhiskConfig
import whisk.core.activator.PostInvoke
import whisk.core.connector.{ ActivationMessage => Message }
import whisk.core.connector.LoadBalancerResponse
import whisk.core.dispatcher.Dispatcher
import whisk.core.entity.ActivationId
import whisk.core.entity.EntityName
import whisk.core.entity.Exec
import whisk.core.entity.Namespace
import whisk.core.entity.Status
import whisk.core.entity.Subject
import whisk.core.entity.WhiskAction
import whisk.core.entity.WhiskRule
import whisk.core.entity.WhiskTrigger

@RunWith(classOf[JUnitRunner])
class DispatcherTests extends FlatSpec with Matchers with BeforeAndAfter {
    implicit val transid = TransactionId.dontcare
    implicit val ec = Dispatcher.executionContext
    val dispatcher = new TestDispatcher("whisk")

    behavior of "Dispatcher"

    def logContains(w: String)(implicit stream: java.io.ByteArrayOutputStream): Boolean = {
        whisk.utils.retry {
            val log = stream.toString()
            log.contains(w)
        }
    }

    it should "send and receive a message from connector bus" in {
        val config = new WhiskConfig(Dispatcher.requiredProperties)
        assert(config.isValid)

        val today = Calendar.getInstance().getTime().toString
        val content = JsObject("payload" -> JsString(today))
        val msg = Message(TransactionId.dontcare, "", Subject(), ActivationId(), Some(content))
        implicit val stream = new java.io.ByteArrayOutputStream
        dispatcher.setVerbosity(Verbosity.Loud)
        Console.withOut(stream) {
            dispatcher.start()
            dispatcher.send(msg)
            logContains("received")
        }
        dispatcher.stop()
        dispatcher.close()
        val logs = stream.toString()
        println(logs)
        logs should include regex (s"received message for 'whisk'.+$today.+")
    }

    it should "receive message from post" in {
        val config = new WhiskConfig(whisk.core.dispatcher.Dispatcher.requiredProperties ++ PostInvoke.requiredProperties)
        assert(config.isValid)

        val today = Calendar.getInstance.getTime.toString
        val msg = Message(TransactionId.dontcare, "", Subject(), ActivationId(), Some(JsObject("payload" -> JsString(today))))
        val namespace = Namespace("post test namespace")
        val dispatcher = new TestDispatcher("invoke0")
        implicit val stream = new java.io.ByteArrayOutputStream
        dispatcher.setVerbosity(Verbosity.Loud)
        Console.withOut(stream) {
            val trigger = WhiskTrigger(namespace, EntityName("post test trigger"))
            val action = WhiskAction(namespace, EntityName("post test action"), Exec.js("code"))
            val rule = WhiskRule(namespace, EntityName("post test rule"), Status.ACTIVE, EntityName("post test trigger"), EntityName("post test action"))
            val post = new PostInvoke("testPostInvoke", rule, Subject(), config) with (HttpRequest => Future[LoadBalancerResponse]) {
                override def apply(r: HttpRequest): Future[LoadBalancerResponse] = {
                    dispatcher.send(msg)
                    Future.successful(LoadBalancerResponse.id(msg.activationId))
                }
            }
            dispatcher.addHandler(post, replace = false)
            dispatcher.start()
            post.doit(msg, Seq()) onFailure { case t => t.printStackTrace() }
            logContains("received")
        }
        dispatcher.stop()
        val logs = stream.toString()
        logs should include regex (s"received message for 'invoke0'.+$today.+")
    }
}

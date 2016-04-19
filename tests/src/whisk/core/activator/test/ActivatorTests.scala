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

package whisk.core.activator.test

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

import akka.actor.ActorSystem

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfter
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.JsObject
import spray.json.pimpAny
import whisk.common.Verbosity
import whisk.core.WhiskConfig
import whisk.core.activator.Activator
import whisk.core.activator.ActivatorService
import whisk.core.connector.{ ActivationMessage => Message }
import whisk.core.database.test.DbUtils
import whisk.core.dispatcher.Dispatcher
import whisk.core.dispatcher.Registrar
import whisk.core.entity.ActivationId
import whisk.core.entity.EntityName
import whisk.core.entity.Namespace
import whisk.core.entity.SemVer
import whisk.core.entity.Status
import whisk.core.entity.Subject
import whisk.core.entity.WhiskEntityStore
import whisk.core.entity.WhiskRule
import scala.language.postfixOps
import scala.language.reflectiveCalls

@RunWith(classOf[JUnitRunner])
class ActivatorTests extends FlatSpec
   with Matchers
   with BeforeAndAfter
   with BeforeAndAfterAll
   with DbUtils {

    implicit val actorSystem = ActorSystem()

    val timeout = 20 seconds
    val namespace = Namespace("activator test namespace")
    val config = new WhiskConfig(
        WhiskEntityStore.requiredProperties ++
            Activator.requiredProperties)
    val datastore = WhiskEntityStore.datastore(config)
    val dispatcher = new Registrar { def cleanup() = handlers.clear() }
    val activator = new Activator(config, dispatcher, ActivatorService.actorSystem, Dispatcher.executionContext)
    var version = SemVer()
    def semver: SemVer = {
        version = version.upPatch
        version
    }

    datastore.setVerbosity(Verbosity.Loud)
    activator.setVerbosity(Verbosity.Loud)

    after {
        cleanup()
        dispatcher.cleanup()
    }

    override def afterAll() {
        println("Shutting down cloudant connections")
        datastore.shutdown()
        println("Shutting down actor system")
        actorSystem.shutdown()
    }

    behavior of "Activator"

    it should "enable a rule" in {
        implicit val tid = transid()
        // start with rule in activating state
        val rule = WhiskRule(namespace, EntityName("enable a rule test"), Status.ACTIVATING, EntityName("a trigger"), EntityName("an action"))
        val message = Message(tid, "", Subject(), ActivationId(), None, None)
        put(datastore, rule)

        // send activator message to activate
        val future = activator.doit(message, activator.matches(s"/rules/${Status.ACTIVATING}/${rule.docid}"))
        future onFailure { case t => println(t) }
        val result = Await.result(future, timeout)

        // get status from database to confirm change
        val updatedRule = get(datastore, result, WhiskRule)
        updatedRule.status should be(Status.ACTIVE)
        rule.docinfo should not be (result) // the revision should be different since activator writes status ACTIVE
    }

    it should "fail when enabling rule with existing handler" in {
        implicit val tid = transid()
        // start with rule in activating state
        val rule = WhiskRule(namespace, EntityName("fail when enabling rule with existing handler"), Status.ACTIVATING, EntityName("a trigger"), EntityName("an action"))
        val message = Message(tid, "", Subject(), ActivationId(), None, None)
        put(datastore, rule)

        // send activator message to activate
        val future = activator.doit(message, activator.matches(s"/rules/${Status.ACTIVATING}/${rule.docid}"))
        future onFailure { case t => println(t) }
        val result = Await.result(future, timeout)

        // get status from database to confirm change
        val updatedRule = get(datastore, result, WhiskRule)
        updatedRule.status should be(Status.ACTIVE)
        rule.docinfo should not be (result) // the revision should be different since activator writes status ACTIVE

        // reset state to activating in datastore
        val newRule = rule.revision[WhiskRule](result.rev)
        put(datastore, newRule)

        intercept[IllegalStateException] {
            val future = activator.doit(message, activator.matches(s"/rules/${Status.ACTIVATING}/${rule.docid}"))
            future onFailure { case t => println(t) }
            val result = Await.result(future, timeout)
        }
    }

    it should "disable a rule" in {
        implicit val tid = transid()
        // start with rule in activating state
        val rule = WhiskRule(namespace, EntityName("disable a rule"), Status.ACTIVATING, EntityName("a trigger"), EntityName("an action"))
        val message = Message(tid, "", Subject(), ActivationId(), None, None)
        put(datastore, rule)

        // send activator message to activate
        val future = activator.doit(message, activator.matches(s"/rules/${Status.ACTIVATING}/${rule.docid}"))
        future onFailure { case t => println(t) }
        val result = Await.result(future, timeout)

        // get status from database to confirm change
        val updatedRule = get(datastore, result, WhiskRule)
        updatedRule.status should be(Status.ACTIVE)
        rule.docinfo should not be (result) // the revision should be different since activator writes status ACTIVE

        // set state to deactivating in datastore
        val newRule = updatedRule.toggle(Status.DEACTIVATING)
        put(datastore, newRule); {
            val future = activator.doit(message, activator.matches(s"/rules/${Status.DEACTIVATING}/${rule.docid}"))
            future onFailure { case t => println(t) }
            val result = Await.result(future, timeout)

            // get status from database to confirm change
            val updatedRule = get(datastore, result, WhiskRule)
            updatedRule.status should be(Status.INACTIVE)
        }
    }

    it should "fail when enabling already enabled rule" in {
        implicit val tid = transid()
        intercept[IllegalArgumentException] {
            val rule = WhiskRule(namespace, EntityName("fail when enabling already enabled rule"), Status.ACTIVE, EntityName("a trigger"), EntityName("an action"))
            val message = Message(tid, "", Subject(), ActivationId(), None, None)
            put(datastore, rule)
            val future = activator.doit(message, activator.matches(s"/rules/${Status.ACTIVATING}/${rule.docid}"))
            future onFailure { case t => println(t) }
            val result = Await.result(future, timeout)
        }
    }

    it should "fail when enabling rule not in required state" in {
        implicit val tid = transid()
        intercept[IllegalArgumentException] {
            val rule = WhiskRule(namespace, EntityName("fail when enabling rule not in required state"), Status.DEACTIVATING, EntityName("a trigger"), EntityName("an action"))
            val message = Message(tid, "", Subject(), ActivationId(), None, None)
            put(datastore, rule)
            val msg = JsObject("subject" -> "some test subject".toJson)
            val future = activator.doit(message, activator.matches(s"/rules/${Status.ACTIVATING}/${rule.docid}"))
            future onFailure { case t => println(t) }
            val result = Await.result(future, timeout)
        }
    }

    it should "fail when disabling already disabled rule" in {
        implicit val tid = transid()
        intercept[IllegalArgumentException] {
            val rule = WhiskRule(namespace, EntityName("fail when disabling already disabled rule"), Status.INACTIVE, EntityName("a trigger"), EntityName("an action"))
            val message = Message(tid, "", Subject(), ActivationId(), None, None)
            put(datastore, rule)
            val msg = JsObject("subject" -> "some test subject".toJson)
            val future = activator.doit(message, activator.matches(s"/rules/${Status.DEACTIVATING}/${rule.docid}"))
            future onFailure { case t => println(t) }
            val result = Await.result(future, timeout)
        }
    }

    it should "fail when disabling rule not in required state" in {
        implicit val tid = transid()
        intercept[IllegalArgumentException] {
            val rule = WhiskRule(namespace, EntityName("fail when disabling rule not in required state"), Status.ACTIVATING, EntityName("a trigger"), EntityName("an action"))
            val message = Message(tid, "", Subject(), ActivationId(), None, None)
            put(datastore, rule)
            val msg = JsObject("subject" -> "some test subject".toJson)
            val future = activator.doit(message, activator.matches(s"/rules/${Status.DEACTIVATING}/${rule.docid}"))
            future onFailure { case t => println(t) }
            val result = Await.result(future, timeout)
        }
    }

    it should "fail when disabling a rule with no handler" in {
        implicit val tid = transid()
        intercept[IllegalStateException] {
            val rule = WhiskRule(namespace, EntityName("fail when disabling a rule with no handler"), Status.DEACTIVATING, EntityName("a trigger"), EntityName("an action"))
            val message = Message(tid, "", Subject(), ActivationId(), None, None)
            put(datastore, rule)
            val msg = JsObject("subject" -> "some test subject".toJson)
            val future = activator.doit(message, activator.matches(s"/rules/${Status.DEACTIVATING}/${rule.docid}"))
            future onFailure { case t => println(t) }
            val result = Await.result(future, timeout)
        }
    }

    it should "fail when arguments do not conform" in {
        implicit val tid = transid()
        intercept[IllegalArgumentException] {
            val future = activator.doit(null, activator.matches(s"/rules/${Status.ACTIVATING}/"))
            future onFailure { case t => println(t) }
            val result = Await.result(future, timeout)
        }

        intercept[IllegalArgumentException] {
            val future = activator.doit(null, activator.matches(s"/rules/${Status.ACTIVATING}/xyz"))
            future onFailure { case t => println(t) }
            val result = Await.result(future, timeout)
        }
    }
}

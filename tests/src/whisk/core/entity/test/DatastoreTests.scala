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

package whisk.core.entity.test

import java.time.Instant

import scala.Vector
import scala.concurrent.Await
import scala.concurrent.duration.Duration

import akka.actor.ActorSystem

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfter
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

import whisk.common.Verbosity
import whisk.core.WhiskConfig
import whisk.core.database.DocumentConflictException
import whisk.core.database.NoDocumentException
import whisk.core.database.test.DbUtils
import whisk.core.entity.ActionLimits
import whisk.core.entity.ActivationId
import whisk.core.entity.ActivationLogs
import whisk.core.entity.ActivationResponse
import whisk.core.entity.AuthKey
import whisk.core.entity.DocInfo
import whisk.core.entity.EntityName
import whisk.core.entity.Exec
import whisk.core.entity.Namespace
import whisk.core.entity.Parameters
import whisk.core.entity.SemVer
import whisk.core.entity.Status
import whisk.core.entity.Subject
import whisk.core.entity.TriggerLimits
import whisk.core.entity.WhiskAction
import whisk.core.entity.WhiskActivation
import whisk.core.entity.WhiskAuth
import whisk.core.entity.WhiskAuthStore
import whisk.core.entity.WhiskEntityStore
import whisk.core.entity.WhiskRule
import whisk.core.entity.WhiskTrigger

@RunWith(classOf[JUnitRunner])
class DatastoreTests extends FlatSpec
    with BeforeAndAfter
    with BeforeAndAfterAll
    with DbUtils {

    implicit val actorSystem = ActorSystem()

    val namespace = Namespace("test namespace")
    val config = new WhiskConfig(WhiskEntityStore.requiredProperties)
    val datastore = WhiskEntityStore.datastore(config)
    val authstore = WhiskAuthStore.datastore(config)
    datastore.setVerbosity(Verbosity.Loud)
    authstore.setVerbosity(Verbosity.Loud)

    override def afterAll() {
        println("Shutting down store connections")
        datastore.shutdown()
        authstore.shutdown()
        println("Shutting down actor system")
        actorSystem.terminate()
        Await.result(actorSystem.whenTerminated, Duration.Inf)
    }

    @volatile var counter = 0
    def aname(implicit n: EntityName) = {
        counter = counter + 1
        EntityName(s"$n$counter")
    }

    after {
        cleanup()
    }

    behavior of "Datastore"

    it should "CRD auth" in {
        implicit val tid = transid()
        val auth = WhiskAuth(Subject(), AuthKey())
        putGetCheck(authstore, auth, WhiskAuth)
    }

    it should "lookup key by uuid" in {
        implicit val tid = transid()
        val auth = WhiskAuth(Subject(), AuthKey())
        put(authstore, auth)
        val confirm = waitOnView(authstore, auth.uuid, 1)
        val result = retry(() => WhiskAuth.get(authstore, auth.uuid), dbOpTimeout).get
        assert(result != null)
        assert(result == auth)
    }

    it should "CRD action blackbox" in {
        implicit val tid = transid()
        implicit val basename = EntityName("create action blackbox")
        val exec = Exec.bb("image")
        val actions = Seq(
            WhiskAction(namespace, aname, exec),
            WhiskAction(namespace, aname, exec, Parameters("x", "y")),
            WhiskAction(namespace, aname, exec, Parameters("x", "y")),
            WhiskAction(namespace, aname, exec, Parameters("x", "y") ++ Parameters("x", "y")),
            WhiskAction(namespace, aname, exec, Parameters("x", "y") ++ Parameters("y", "x")))
        val docs = actions.map { entity =>
            putGetCheck(datastore, entity, WhiskAction)
        }
    }

    it should "CRD action js" in {
        implicit val tid = transid()
        implicit val basename = EntityName("create action js")
        val exec = Exec.js("code")
        val actions = Seq(
            WhiskAction(namespace, aname, exec, Parameters()),
            WhiskAction(namespace, aname, exec, Parameters("x", "y")),
            WhiskAction(namespace, aname, exec, Parameters("x", "y")),
            WhiskAction(namespace, aname, exec, Parameters("x", "y") ++ Parameters("x", "y")),
            WhiskAction(namespace, aname, exec, Parameters("x", "y") ++ Parameters("y", "x")))
        val docs = actions.map { entity =>
            putGetCheck(datastore, entity, WhiskAction)
        }
    }

    it should "CRD trigger" in {
        implicit val tid = transid()
        implicit val basename = EntityName("create trigger")
        val triggers = Seq(
            WhiskTrigger(namespace, aname),
            WhiskTrigger(namespace, aname, Parameters("x", "y")),
            WhiskTrigger(namespace, aname, Parameters("x", "y")))
        val docs = triggers.map { entity =>
            putGetCheck(datastore, entity, WhiskTrigger)
        }
    }

    it should "CRD rule" in {
        implicit val tid = transid()
        implicit val basename = EntityName("create rule")
        val rules = Seq(
            WhiskRule(namespace, aname, EntityName("a trigger"), EntityName("an action")),
            WhiskRule(namespace, aname, EntityName("a trigger"), EntityName("an action")))
        val docs = rules.map { entity =>
            putGetCheck(datastore, entity, WhiskRule)
        }
    }

    it should "CRD activation" in {
        implicit val tid = transid()
        implicit val basename = EntityName("create action blackbox")
        val activations = Seq(
            WhiskActivation(namespace, aname, Subject(), ActivationId(), start = Instant.now, end = Instant.now),
            WhiskActivation(namespace, aname, Subject(), ActivationId(), start = Instant.now, end = Instant.now))
        val docs = activations.map { entity =>
            putGetCheck(datastore, entity, WhiskActivation)
        }
    }

    ignore should "CRD activation with utf8 characters" in {
        implicit val tid = transid()
        implicit val basename = EntityName("create action blackbox")
        val activations = Seq(
            WhiskActivation(namespace, aname, Subject(), ActivationId(), start = Instant.now, end = Instant.now, logs = ActivationLogs(Vector("Prote\u00EDna"))))
        val docs = activations.map { entity =>
            putGetCheck(datastore, entity, WhiskActivation)
        }
    }

    it should "reject action with null arguments" in {
        val name = EntityName("bad action")
        intercept[IllegalArgumentException] {
            WhiskAction(namespace, name, Exec.bb("i"), Parameters(), null)
        }
    }

    it should "reject trigger with null arguments" in {
        val name = EntityName("bad trigger")
        intercept[IllegalArgumentException] {
            WhiskTrigger(namespace, name, Parameters(), null)
        }
    }

    it should "reject rule with null arguments" in {
        val name = EntityName("bad rule")
        intercept[IllegalArgumentException] {
            WhiskRule(namespace, name, EntityName(null), EntityName(null))
        }
        intercept[IllegalArgumentException] {
            WhiskRule(namespace, name, EntityName(""), EntityName(null))
        }
        intercept[IllegalArgumentException] {
            WhiskRule(namespace, name, EntityName(" "), EntityName(null))
        }
    }

    it should "update auth with a revision" in {
        implicit val tid = transid()
        val auth = WhiskAuth(Subject(), AuthKey())
        val docinfo = putGetCheck(authstore, auth, WhiskAuth, false)._2.docinfo
        val revAuth = auth.revoke.revision[WhiskAuth](docinfo.rev)
        putGetCheck(authstore, revAuth, WhiskAuth)
    }

    it should "update action with a revision" in {
        implicit val tid = transid()
        implicit val basename = EntityName("update action")
        val exec = Exec.js("update")
        val action = WhiskAction(namespace, aname, exec, Parameters(), ActionLimits())
        val docinfo = putGetCheck(datastore, action, WhiskAction, false)._2.docinfo
        val revAction = WhiskAction(namespace, action.name, exec, Parameters(), ActionLimits()).revision[WhiskAction](docinfo.rev)
        putGetCheck(datastore, revAction, WhiskAction)
    }

    it should "update trigger with a revision" in {
        implicit val tid = transid()
        implicit val basename = EntityName("update trigger")
        val trigger = WhiskTrigger(namespace, aname)
        val docinfo = putGetCheck(datastore, trigger, WhiskTrigger, false)._2.docinfo
        val revTrigger = WhiskTrigger(namespace, trigger.name).revision[WhiskTrigger](docinfo.rev)
        putGetCheck(datastore, revTrigger, WhiskTrigger)
    }

    it should "update rule with a revision" in {
        implicit val tid = transid()
        implicit val basename = EntityName("update rule")
        val rule = WhiskRule(namespace, aname, EntityName("a trigger"), EntityName("an action"))
        val docinfo = putGetCheck(datastore, rule, WhiskRule, false)._2.docinfo
        val revRule = WhiskRule(namespace, rule.name, rule.trigger, rule.action).revision[WhiskRule](docinfo.rev)
        putGetCheck(datastore, revRule, WhiskRule)
    }

    it should "update activation with a revision" in {
        implicit val tid = transid()
        implicit val basename = EntityName("update activation")
        val activation = WhiskActivation(namespace, aname, Subject(), ActivationId(), start = Instant.now, end = Instant.now)
        val docinfo = putGetCheck(datastore, activation, WhiskActivation, false)._2.docinfo
        val revActivation = WhiskActivation(namespace, aname, activation.subject, activation.activationId, start = Instant.now, end = Instant.now).revision[WhiskActivation](docinfo.rev)
        putGetCheck(datastore, revActivation, WhiskActivation)
    }

    it should "fail with document conflict when trying to write the same auth twice without a revision" in {
        implicit val tid = transid()
        val auth = WhiskAuth(Subject(), AuthKey())
        putGetCheck(authstore, auth, WhiskAuth)
        intercept[DocumentConflictException] {
            putGetCheck(authstore, auth, WhiskAuth)
        }
    }

    it should "fail with document conflict when trying to write the same action twice without a revision" in {
        implicit val tid = transid()
        implicit val basename = EntityName("create action twice")
        val exec = Exec.js("twice")
        val action = WhiskAction(namespace, aname, exec)
        putGetCheck(datastore, action, WhiskAction)
        intercept[DocumentConflictException] {
            putGetCheck(datastore, action, WhiskAction)
        }
    }

    it should "fail with document conflict when trying to write the same trigger twice without a revision" in {
        implicit val tid = transid()
        implicit val basename = EntityName("create trigger twice")
        val trigger = WhiskTrigger(namespace, aname, Parameters("x", "y"))
        putGetCheck(datastore, trigger, WhiskTrigger)
        intercept[DocumentConflictException] {
            putGetCheck(datastore, trigger, WhiskTrigger)
        }
    }

    it should "fail with document conflict when trying to write the same rule twice without a revision" in {
        implicit val tid = transid()
        implicit val basename = EntityName("create rule twice")
        val rule = WhiskRule(namespace, aname, EntityName("a trigger"), EntityName("an action"))
        putGetCheck(datastore, rule, WhiskRule)
        intercept[DocumentConflictException] {
            putGetCheck(datastore, rule, WhiskRule)
        }
    }

    it should "fail with document conflict when trying to write the same activation twice without a revision" in {
        implicit val tid = transid()
        implicit val basename = EntityName("create activation twice")
        val activation = WhiskActivation(namespace, aname, Subject(), ActivationId(), start = Instant.now, end = Instant.now)
        putGetCheck(datastore, activation, WhiskActivation)
        intercept[DocumentConflictException] {
            putGetCheck(datastore, activation, WhiskActivation)
        }
    }

    it should "fail with document does not exist when trying to delete the same auth twice" in {
        implicit val tid = transid()
        val auth = WhiskAuth(Subject(), AuthKey())
        val doc = putGetCheck(authstore, auth, WhiskAuth, false)._1
        assert(Await.result(WhiskAuth.del(authstore, doc), dbOpTimeout))
        intercept[NoDocumentException] {
            assert(Await.result(WhiskAuth.del(authstore, doc), dbOpTimeout))
            assert(false)
        }
    }

    it should "fail with document does not exist when trying to delete the same action twice" in {
        implicit val tid = transid()
        implicit val basename = EntityName("delete action twice")
        val exec = Exec.js("twice")
        val action = WhiskAction(namespace, aname, exec)
        val doc = putGetCheck(datastore, action, WhiskAction, false)._1
        assert(Await.result(WhiskAction.del(datastore, doc), dbOpTimeout))
        intercept[NoDocumentException] {
            Await.result(WhiskAction.del(datastore, doc), dbOpTimeout)
            assert(false)
        }
    }

    it should "fail with document does not exist when trying to delete the same trigger twice" in {
        implicit val tid = transid()
        implicit val basename = EntityName("delete trigger twice")
        val trigger = WhiskTrigger(namespace, aname, Parameters("x", "y"))
        val doc = putGetCheck(datastore, trigger, WhiskTrigger, false)._1
        assert(Await.result(WhiskTrigger.del(datastore, doc), dbOpTimeout))
        intercept[NoDocumentException] {
            Await.result(WhiskTrigger.del(datastore, doc), dbOpTimeout)
            assert(false)
        }
    }

    it should "fail with document does not exist when trying to delete the same rule twice" in {
        implicit val tid = transid()
        implicit val basename = EntityName("delete rule twice")
        val rule = WhiskRule(namespace, aname, EntityName("a trigger"), EntityName("an action"))
        val doc = putGetCheck(datastore, rule, WhiskRule, false)._1
        assert(Await.result(WhiskRule.del(datastore, doc), dbOpTimeout))
        intercept[NoDocumentException] {
            Await.result(WhiskRule.del(datastore, doc), dbOpTimeout)
            assert(false)
        }
    }

    it should "fail with document does not exist when trying to delete the same activation twice" in {
        implicit val tid = transid()
        implicit val basename = EntityName("delete activation twice")
        val activation = WhiskActivation(namespace, aname, Subject(), ActivationId(), start = Instant.now, end = Instant.now)
        val doc = putGetCheck(datastore, activation, WhiskActivation, false)._1
        assert(Await.result(WhiskActivation.del(datastore, doc), dbOpTimeout))
        intercept[NoDocumentException] {
            Await.result(WhiskActivation.del(datastore, doc), dbOpTimeout)
            assert(false)
        }
    }

    it should "fail to CRD with undefined argument" in {
        implicit val tid = transid()
        intercept[IllegalArgumentException] {
            Await.result(WhiskAction.del(null, null), dbOpTimeout)
            assert(false)
        }
        intercept[IllegalArgumentException] {
            Await.result(WhiskAction.put(null, null), dbOpTimeout)
            assert(false)
        }
        intercept[IllegalArgumentException] {
            Await.result(WhiskAction.get(datastore, null), dbOpTimeout)
            assert(false)
        }
        intercept[IllegalArgumentException] {
            Await.result(WhiskTrigger.get(datastore, null), dbOpTimeout)
            assert(false)
        }
        intercept[IllegalArgumentException] {
            Await.result(WhiskRule.get(datastore, null), dbOpTimeout)
            assert(false)
        }
        intercept[IllegalArgumentException] {
            Await.result(WhiskActivation.get(datastore, null), dbOpTimeout)
            assert(false)
        }
        intercept[IllegalArgumentException] {
            Await.result(WhiskAuth.get(authstore, null: DocInfo), dbOpTimeout)
            assert(false)
        }
    }
}

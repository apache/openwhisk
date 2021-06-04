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

package org.apache.openwhisk.core.entity.test

import scala.concurrent.Await
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterEach
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner
import common.{StreamLogging, WskActorSystem}
import org.apache.openwhisk.common.WhiskInstants
import org.mockito.Mockito._
import org.apache.openwhisk.core.database.DocumentConflictException
import org.apache.openwhisk.core.database.CacheChangeNotification
import org.apache.openwhisk.core.database.NoDocumentException
import org.apache.openwhisk.core.database.test.DbUtils
import org.apache.openwhisk.core.entity._

@RunWith(classOf[JUnitRunner])
class DatastoreTests
    extends FlatSpec
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with WskActorSystem
    with DbUtils
    with ExecHelpers
    with StreamLogging
    with WhiskInstants {

  val namespace = EntityPath("test namespace")
  val datastore = WhiskEntityStore.datastore()
  val authstore = WhiskAuthStore.datastore()

  implicit val cacheUpdateNotifier: Option[CacheChangeNotification] = None

  override def afterEach() = {
    cleanup()
  }

  override def afterAll() = {
    println("Shutting down store connections")
    datastore.shutdown()
    authstore.shutdown()
    super.afterAll()
  }

  @volatile var counter = 0
  def aname(implicit n: EntityName) = {
    counter = counter + 1
    EntityName(s"$n$counter")
  }

  def afullname(implicit namespace: EntityPath, name: String) = FullyQualifiedEntityName(namespace, EntityName(name))

  behavior of "Datastore"

  it should "CRD action blackbox" in {
    implicit val tid = transid()
    implicit val basename = EntityName("create action blackbox")
    val exec = bb("image")
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
    val exec = jsDefault("code")
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
      WhiskRule(namespace, aname, afullname(namespace, "a trigger"), afullname(namespace, "an action")),
      WhiskRule(namespace, aname, afullname(namespace, "a trigger"), afullname(namespace, "an action")))
    val docs = rules.map { entity =>
      putGetCheck(datastore, entity, WhiskRule)
    }
  }

  it should "CRD activation" in {
    implicit val tid = transid()
    implicit val basename = EntityName("create action blackbox")
    val activations = Seq(
      WhiskActivation(namespace, aname, Subject(), ActivationId.generate(), start = nowInMillis(), end = nowInMillis()),
      WhiskActivation(namespace, aname, Subject(), ActivationId.generate(), start = nowInMillis(), end = nowInMillis()))
    val docs = activations.map { entity =>
      putGetCheck(datastore, entity, WhiskActivation)
    }
  }

  it should "CRD activation with utf8 characters" in {
    implicit val tid = transid()
    implicit val basename = EntityName("create action blackbox")
    val activations = Seq(
      WhiskActivation(
        namespace,
        aname,
        Subject(),
        ActivationId.generate(),
        start = nowInMillis(),
        end = nowInMillis(),
        logs = ActivationLogs(Vector("Prote\u00EDna"))))
    val docs = activations.map { entity =>
      putGetCheck(datastore, entity, WhiskActivation)
    }
  }

  it should "reject action with null arguments" in {
    val name = EntityName("bad action")
    intercept[IllegalArgumentException] {
      WhiskAction(namespace, name, bb("i"), Parameters(), null)
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
      WhiskRule(
        namespace,
        name,
        FullyQualifiedEntityName(namespace, EntityName(null)),
        FullyQualifiedEntityName(namespace, EntityName(null)))
    }
    intercept[IllegalArgumentException] {
      WhiskRule(
        namespace,
        name,
        FullyQualifiedEntityName(namespace, EntityName("")),
        FullyQualifiedEntityName(namespace, EntityName(null)))
    }
    intercept[IllegalArgumentException] {
      WhiskRule(
        namespace,
        name,
        FullyQualifiedEntityName(namespace, EntityName(" ")),
        FullyQualifiedEntityName(namespace, EntityName(null)))
    }
  }

  it should "update action with a revision" in {
    implicit val tid = transid()
    implicit val basename = EntityName("update action")
    val exec = jsDefault("update")
    val action = WhiskAction(namespace, aname, exec, Parameters(), ActionLimits())
    val docinfo = putGetCheck(datastore, action, WhiskAction, false)._2.docinfo
    val revAction =
      WhiskAction(namespace, action.name, exec, Parameters(), ActionLimits()).revision[WhiskAction](docinfo.rev)
    putGetCheck(datastore, revAction, WhiskAction)
  }

  it should "delete action attachments" in {
    implicit val tid = transid()
    implicit val basename = EntityName("attachment action")
    val javaAction =
      WhiskAction(namespace, aname, javaDefault("ZHViZWU=", Some("hello")), annotations = Parameters("exec", "java"))
    val docinfo = putGetCheck(datastore, javaAction, WhiskAction, false)._2.docinfo

    val proxy = spy(datastore)
    Await.result(WhiskAction.del(proxy, docinfo), dbOpTimeout)

    verify(proxy).deleteAttachments(docinfo)
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
    val rule = WhiskRule(namespace, aname, afullname(namespace, "a trigger"), afullname(namespace, "an action"))
    val docinfo = putGetCheck(datastore, rule, WhiskRule, false)._2.docinfo
    val revRule = WhiskRule(namespace, rule.name, rule.trigger, rule.action).revision[WhiskRule](docinfo.rev)
    putGetCheck(datastore, revRule, WhiskRule)
  }

  it should "update activation with a revision" in {
    implicit val tid = transid()
    implicit val basename = EntityName("update activation")
    val activation =
      WhiskActivation(namespace, aname, Subject(), ActivationId.generate(), start = nowInMillis(), end = nowInMillis())
    val docinfo = putGetCheck(datastore, activation, WhiskActivation, false)._2.docinfo
    val revActivation = WhiskActivation(
      namespace,
      aname,
      activation.subject,
      activation.activationId,
      start = nowInMillis(),
      end = nowInMillis()).revision[WhiskActivation](docinfo.rev)
    putGetCheck(datastore, revActivation, WhiskActivation)
  }

  it should "fail with document conflict when trying to write the same action twice without a revision" in {
    implicit val tid = transid()
    implicit val basename = EntityName("create action twice")
    val exec = jsDefault("twice")
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
    val rule = WhiskRule(namespace, aname, afullname(namespace, "a trigger"), afullname(namespace, "an action"))
    putGetCheck(datastore, rule, WhiskRule)
    intercept[DocumentConflictException] {
      putGetCheck(datastore, rule, WhiskRule)
    }
  }

  it should "fail with document conflict when trying to write the same activation twice without a revision" in {
    implicit val tid = transid()
    implicit val basename = EntityName("create activation twice")
    val activation =
      WhiskActivation(namespace, aname, Subject(), ActivationId.generate(), start = nowInMillis(), end = nowInMillis())
    putGetCheck(datastore, activation, WhiskActivation)
    intercept[DocumentConflictException] {
      putGetCheck(datastore, activation, WhiskActivation)
    }
  }

  it should "fail with document does not exist when trying to delete the same action twice" in {
    implicit val tid = transid()
    implicit val basename = EntityName("delete action twice")
    val exec = jsDefault("twice")
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
    val rule = WhiskRule(namespace, aname, afullname(namespace, "a trigger"), afullname(namespace, "an action"))
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
    val activation =
      WhiskActivation(namespace, aname, Subject(), ActivationId.generate(), start = nowInMillis(), end = nowInMillis())
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
      Await.result(WhiskAction.put(null, null, None), dbOpTimeout)
      assert(false)
    }
  }
}

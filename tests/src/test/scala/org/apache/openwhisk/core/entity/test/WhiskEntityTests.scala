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

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import org.apache.openwhisk.core.entity.EntityPath
import org.apache.openwhisk.core.entity.EntityName
import org.apache.openwhisk.core.entity.WhiskAction
import org.apache.openwhisk.core.entity.DocRevision
import org.apache.openwhisk.core.entity.Parameters
import org.apache.openwhisk.core.entity.FullyQualifiedEntityName
import org.apache.openwhisk.core.entity.SequenceExec
import org.apache.openwhisk.core.entity.WhiskPackage
import org.apache.openwhisk.core.entity.WhiskActivation
import org.apache.openwhisk.core.entity.Subject
import org.apache.openwhisk.core.entity.ActivationId
import org.apache.openwhisk.core.entity.WhiskDocumentReader
import java.time.Instant

import spray.json._
import org.apache.openwhisk.core.entity.ActivationLogs
import org.apache.openwhisk.core.entity.WhiskTrigger
import org.apache.openwhisk.core.entity.ReducedRule
import org.apache.openwhisk.core.entity.Status
import org.apache.openwhisk.core.entity.WhiskEntity
import org.apache.openwhisk.core.entity.WhiskRule
import org.apache.openwhisk.core.database.DocumentTypeMismatchException

@RunWith(classOf[JUnitRunner])
class WhiskEntityTests extends FlatSpec with ExecHelpers with Matchers {

  val namespace = EntityPath("testspace")
  val name = EntityName("testname")
  val revision = DocRevision("test")

  behavior of "WhiskAction"

  it should "correctly inherit parameters and preserve revision through the process" in {
    def withParameters(p: Parameters) =
      WhiskAction(namespace, name, jsDefault("js1"), parameters = p).revision[WhiskAction](revision)

    val toInherit = Parameters("testParam", "testValue")
    Seq(Parameters(), Parameters("testParam2", "testValue"), Parameters("testParam", "testValue2")).foreach { params =>
      val action = withParameters(params)
      val inherited = action.inherit(toInherit)
      inherited shouldBe action.copy(parameters = toInherit ++ action.parameters)
      inherited.rev shouldBe action.rev
    }
  }

  it should "correctly resolve default namespace and preserve its revision through the process" in {
    val user = "testuser"
    val sequenceAction = FullyQualifiedEntityName(EntityPath("_"), EntityName("testaction"))
    val action = WhiskAction(namespace, name, sequence(Vector(sequenceAction))).revision[WhiskAction](revision)

    val resolved = action.resolve(EntityName(user))
    resolved.exec.asInstanceOf[SequenceExec].components.head shouldBe sequenceAction.copy(path = EntityPath(user))
    action.rev shouldBe resolved.rev
  }

  behavior of "WhiskPackage"

  it should "correctly inherit parameters and preserve revision through the process" in {
    def withParameters(p: Parameters) = WhiskPackage(namespace, name, parameters = p).revision[WhiskPackage](revision)

    val toInherit = Parameters("testParam", "testValue")
    Seq(Parameters(), Parameters("testParam2", "testValue"), Parameters("testParam", "testValue2")).foreach { params =>
      val pkg = withParameters(params)
      val inherited = pkg.inherit(toInherit)
      inherited shouldBe pkg.copy(parameters = toInherit ++ pkg.parameters)
      inherited.rev shouldBe pkg.rev
    }
  }

  it should "correctly merge parameters and preserve revision through the process" in {
    def withParameters(p: Parameters) = WhiskPackage(namespace, name, parameters = p).revision[WhiskPackage](revision)

    val toOverride = Parameters("testParam", "testValue")
    Seq(Parameters(), Parameters("testParam2", "testValue"), Parameters("testParam", "testValue2")).foreach { params =>
      val pkg = withParameters(params)
      val inherited = pkg.mergeParameters(toOverride)
      inherited shouldBe pkg.copy(parameters = pkg.parameters ++ toOverride)
      inherited.rev shouldBe pkg.rev
    }
  }

  behavior of "WhiskActivation"

  it should "add and remove logs and preserve revision in the process" in {
    val activation = WhiskActivation(namespace, name, Subject(), ActivationId.generate(), Instant.now(), Instant.now())
      .revision[WhiskActivation](revision)
    val logs = ActivationLogs(Vector("testlog"))

    val withLogs = activation.withLogs(logs)
    withLogs shouldBe activation.copy(logs = logs)
    withLogs.rev shouldBe activation.rev

    val withoutLogs = withLogs.withoutLogs
    withoutLogs shouldBe activation
    withoutLogs.rev shouldBe activation.rev
  }

  behavior of "WhiskTrigger"

  it should "add and remove rules and preserve revision in the process" in {
    val fqn = FullyQualifiedEntityName(namespace, name)
    val trigger = WhiskTrigger(namespace, name).revision[WhiskTrigger](revision)
    val rule = ReducedRule(fqn, Status.ACTIVE)

    // Add a rule
    val ruleAdded = trigger.addRule(fqn, rule)
    ruleAdded.rules shouldBe Some(Map(fqn -> rule))
    ruleAdded.rev shouldBe trigger.rev

    // Remove the rule
    val ruleRemoved = ruleAdded.removeRule(fqn)
    ruleRemoved.rules shouldBe Some(Map.empty[FullyQualifiedEntityName, ReducedRule])
    ruleRemoved.rev shouldBe trigger.rev

    // Remove all rules
    val rulesRemoved = ruleAdded.withoutRules
    rulesRemoved.rules shouldBe None
    rulesRemoved.rev shouldBe trigger.rev
  }

  behavior of "WhiskEntity"

  it should "define the entityType property in its json representation" in {

    val action = WhiskAction(namespace, name, jsDefault("code"), Parameters())
    assertType(action, "action")

    val activation = WhiskActivation(namespace, name, Subject(), ActivationId.generate(), Instant.now(), Instant.now())
    assertType(activation, "activation")

    val whiskPackage = WhiskPackage(namespace, name)
    assertType(whiskPackage, "package")

    val rule =
      WhiskRule(namespace, name, FullyQualifiedEntityName(namespace, name), FullyQualifiedEntityName(namespace, name))
    assertType(rule, "rule")

    val trigger = WhiskTrigger(namespace, name)
    assertType(trigger, "trigger")
  }

  behavior of "WhiskDocumentReader"

  it should "check entityType when deserialize" in {
    def assertType(d: WhiskEntity, entityType: String) = {
      d.toDocumentRecord.fields("entityType") shouldBe JsString(entityType)
    }

    val json =
      """{
        |	"name": "action_test",
        |	"publish": false,
        |	"annotations": [],
        |	"version": "0.0.2",
        |	"entityType": "action",
        |	"exec": {
        |		"kind": "nodejs:14",
        |		"code": "foo",
        |		"binary": false
        |	},
        |	"parameters": [],
        |	"limits": {
        |		"timeout": 60000,
        |		"memory": 256
        |	},
        |	"namespace": "namespace",
        |	"updated": 1546268400000
        |}""".stripMargin.parseJson

    val action = WhiskDocumentReader.read(manifest[WhiskAction], json)
    assertType(action.asInstanceOf[WhiskEntity], "action")
    assertThrows[DocumentTypeMismatchException] {
      WhiskDocumentReader.read(manifest[WhiskTrigger], json)
    }
  }

  it should "deserialize without entityType" in {
    val json =
      """{
        |  "name": "action_test",
        |  "publish": false,
        |  "annotations": [],
        |  "version": "0.0.1",
        |  "exec": {
        |	   "kind": "nodejs:14",
        |    "code": "foo",
        |    "binary": false
        |  },
        |  "parameters": [],
        |  "limits": {
        |    "timeout": 60000,
        |    "memory": 256
        |  },
        |  "namespace": "namespace",
        |  "updated": 1546268400000
        |}""".stripMargin.parseJson
    val action = WhiskDocumentReader.read(manifest[WhiskAction], json)
    assertType(action.asInstanceOf[WhiskEntity], "action")
  }

  protected def assertType(d: WhiskEntity, entityType: String) = {
    d.toDocumentRecord.fields("entityType") shouldBe JsString(entityType)
  }
}

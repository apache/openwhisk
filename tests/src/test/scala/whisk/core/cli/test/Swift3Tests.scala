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

package whisk.core.cli.test

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import org.junit.runner.RunWith
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import common.TestHelpers
import common.TestUtils
import common.Wsk
import common.WskProps
import common.WskTestHelpers
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.pimpAny

@RunWith(classOf[JUnitRunner])
class Swift3Tests extends TestHelpers with WskTestHelpers with Matchers {

  implicit val wskprops = WskProps()
  val wsk = new Wsk
  val expectedDuration = 45 seconds
  val activationPollDuration = 60 seconds

  lazy val runtimeContainer = "swift:3"

  behavior of "Swift Actions"

  /**
   * Test the Swift "hello world" demo sequence
   */
  it should "invoke a swift 3 action" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "helloSwift"
    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, Some(TestUtils.getTestActionFilename("hello.swift")))
    }

    val start = System.currentTimeMillis()
    withActivation(wsk.activation, wsk.action.invoke(name), totalWait = activationPollDuration) {
      _.response.result.get.toString should include("Hello stranger!")
    }

    withActivation(
      wsk.activation,
      wsk.action.invoke(name, Map("name" -> "Sir".toJson)),
      totalWait = activationPollDuration) {
      _.response.result.get.toString should include("Hello Sir!")
    }

    withClue("Test duration exceeds expectation (ms)") {
      val duration = System.currentTimeMillis() - start
      duration should be <= expectedDuration.toMillis
    }
  }

  behavior of "Swift 3 Whisk SDK tests"

  it should "allow Swift actions to invoke other actions" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    // use CLI to create action from dat/actions/invokeAction.swift
    val file = TestUtils.getTestActionFilename("invoke.swift")
    val actionName = "invokeAction"
    assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
      action.create(name = actionName, artifact = Some(file), kind = Some(runtimeContainer))
    }

    // invoke the action
    val run = wsk.action.invoke(actionName)
    withActivation(wsk.activation, run, initialWait = 5 seconds, totalWait = 60 seconds) { activation =>
      // should be successful
      activation.response.success shouldBe true

      // should have a field named "activationId" which is the date action's activationId
      activation.response.result.get.fields("activationId").toString.length should be >= 32

      // check for "date" field that comes from invoking the date action
      //activation.response.result.get.fieldPathExists("response", "result", "date") should be(true)
    }
  }

  it should "allow Swift actions to invoke other actions and not block" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      // use CLI to create action from dat/actions/invokeNonBlocking.swift
      val file = TestUtils.getTestActionFilename("invokeNonBlocking.swift")
      val actionName = "invokeNonBlockingAction"
      assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
        action.create(name = actionName, artifact = Some(file), kind = Some(runtimeContainer))
      }

      // invoke the action
      val run = wsk.action.invoke(actionName)
      withActivation(wsk.activation, run, initialWait = 5 seconds, totalWait = 60 seconds) { activation =>
        // should not have a "response"
        whisk.utils.JsHelpers.fieldPathExists(activation.response.result.get, "response") shouldBe false

        // should have a field named "activationId" which is the date action's activationId
        activation.response.result.get.fields("activationId").toString.length should be >= 32
      }
  }

  it should "allow Swift actions to trigger events" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    // create a trigger
    val triggerName = s"TestTrigger ${System.currentTimeMillis()}"
    assetHelper.withCleaner(wsk.trigger, triggerName) { (trigger, _) =>
      trigger.create(triggerName)
    }

    // create an action that fires the trigger
    val file = TestUtils.getTestActionFilename("trigger.swift")
    val actionName = "ActionThatTriggers"
    assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
      action.create(name = actionName, artifact = Some(file), kind = Some(runtimeContainer))
    }

    // invoke the action
    val run = wsk.action.invoke(actionName, Map("triggerName" -> triggerName.toJson))
    withActivation(wsk.activation, run, initialWait = 5 seconds, totalWait = 60 seconds) { activation =>
      // should be successful
      activation.response.success shouldBe true

      // should have a field named "activationId" which is the date action's activationId
      activation.response.result.get.fields("activationId").toString.length should be >= 32

      // should result in an activation for triggerName
      val triggerActivations = wsk.activation.pollFor(1, Some(triggerName), retries = 20)
      withClue(s"trigger activations for $triggerName:") {
        triggerActivations.length should be(1)
      }
    }
  }

  it should "allow Swift actions to create a trigger" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    // create a trigger
    val triggerName = s"TestTrigger ${System.currentTimeMillis()}"

    // create an action that creates the trigger
    val file = TestUtils.getTestActionFilename("createTrigger.swift")
    val actionName = "ActionThatTriggers"
    assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
      action.create(name = actionName, artifact = Some(file), kind = Some("swift:3"))
    }

    // invoke the action
    val run = wsk.action.invoke(actionName, Map("triggerName" -> triggerName.toJson))
    withActivation(wsk.activation, run, initialWait = 5 seconds, totalWait = 60 seconds) { activation =>
      // should be successful
      activation.response.success shouldBe true

      // should have a field named "name" which is the name of the trigger created
      activation.response.result.get.fields("name").toString.length should be >= 20
    }
  }

  it should "allow Swift actions to create a rule" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    // create a trigger name
    val ruleTriggerName = s"/_/TestTrigger ${System.currentTimeMillis()}"

    // create an action name
    val ruleActionName = s"/_/TestAction ${System.currentTimeMillis()}"

    // create a name for our rule
    val ruleName = s"TestRule ${System.currentTimeMillis()}"

    // create an action that creates the trigger which we will use when creating the rule
    val createTriggerFile = TestUtils.getTestActionFilename("createTrigger.swift")
    assetHelper.withCleaner(wsk.action, "ActionThatCreatesTrigger") { (action, _) =>
      action.create(name = "ActionThatCreatesTrigger", artifact = Some(createTriggerFile), kind = Some("swift:3"))
    }

    // invoke the create trigger action
    val runCreateTrigger = wsk.action.invoke("ActionThatCreatesTrigger", Map("triggerName" -> ruleTriggerName.toJson))
    withActivation(wsk.activation, runCreateTrigger, initialWait = 5 seconds, totalWait = 60 seconds) { activation =>
      // should be successful
      activation.response.success shouldBe true
    }

    // create a dummy action for the rule
    val dummyFile = TestUtils.getTestActionFilename("hello.swift")
    assetHelper.withCleaner(wsk.action, ruleActionName) { (action, _) =>
      action.create(name = ruleActionName, artifact = Some(dummyFile), kind = Some("swift:3"))
    }

    // create an action that creates the rule
    val createRuleFile = TestUtils.getTestActionFilename("createRule.swift")
    assetHelper.withCleaner(wsk.action, "ActionThatCreatesRule") { (action, _) =>
      action.create(name = "ActionThatCreatesRule", artifact = Some(createRuleFile), kind = Some("swift:3"))
    }

    // invoke the create rule action
    val runCreateRule = wsk.action.invoke(
      "ActionThatCreatesRule",
      Map(
        "triggerName" -> ruleTriggerName.toJson,
        "actionName" -> ruleActionName.toJson,
        "ruleName" -> ruleName.toJson))
    withActivation(wsk.activation, runCreateRule, initialWait = 5 seconds, totalWait = 60 seconds) { activation =>
      // should be successful
      activation.response.success shouldBe true

      // should have a field named "trigger" which is the name of the trigger associated with the rule
      activation.response.result.get.fields("trigger").toString.length should be >= 20

      // should have a field named "action" which is the name of the action associated with the rule
      activation.response.result.get.fields("action").toString.length should be >= 20
    }

    // Now fire the trigger
    val file = TestUtils.getTestActionFilename("trigger.swift")
    val actionName = "ActionThatTriggersRule"
    assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
      action.create(name = actionName, artifact = Some(file), kind = Some("swift:3"))
    }

    // invoke the action to fire the trigger for the rule
    val run = wsk.action.invoke(actionName, Map("triggerName" -> ruleTriggerName.toJson))
    withActivation(wsk.activation, run, initialWait = 5 seconds, totalWait = 60 seconds) { activation =>
      // should be successful
      activation.response.success shouldBe true

      // should have a field named "activationId" which is the trigger activationId
      activation.response.result.get.fields("activationId").toString.length should be >= 32

      // should result in an activation for the rule
      val ruleActivations = wsk.activation.pollFor(1, Some(ruleName), retries = 20)
      withClue(s"rule activations for $ruleName:") {
        ruleActivations.length should be(1)
      }

    }
  }
}

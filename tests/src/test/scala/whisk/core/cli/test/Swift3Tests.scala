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
import common.rest.WskRest
import common.WskProps
import common.WskTestHelpers
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.pimpAny

@RunWith(classOf[JUnitRunner])
class Swift3Tests extends TestHelpers with WskTestHelpers with Matchers {

  implicit val wskprops = WskProps()
  val wsk = new WskRest
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
    // create an action that creates the trigger
    val file = TestUtils.getTestActionFilename("createTrigger.swift")
    val actionName = "ActionThatTriggers"

    // the name of the trigger to create
    val triggerName = s"TestTrigger ${System.currentTimeMillis()}"

    assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
      assetHelper.withCleaner(wsk.trigger, triggerName) { (_, _) =>
        // using an asset cleaner on the created trigger name will clean it up at the conclusion of the test
        action.create(name = actionName, artifact = Some(file), kind = Some("swift:3"))
      }
    }

    // invoke the action
    val run = wsk.action.invoke(actionName, Map("triggerName" -> triggerName.toJson))
    withActivation(wsk.activation, run, initialWait = 5 seconds, totalWait = 60 seconds) { activation =>
      // should be successful
      activation.response.success shouldBe true

      // should have a field named "name" which is the name of the trigger created
      activation.response.result.get.fields("name") shouldBe triggerName.toJson
    }
  }

  it should "allow Swift actions to create a rule" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val ruleTriggerName = s"TestTrigger ${System.currentTimeMillis()}"
    val ruleActionName = s"TestAction ${System.currentTimeMillis()}"
    val ruleName = s"TestRule ${System.currentTimeMillis()}"

    // create a dummy action and trigger for the rule
    assetHelper.withCleaner(wsk.action, ruleActionName) { (action, name) =>
      val dummyFile = TestUtils.getTestActionFilename("hello.swift")
      action.create(name, artifact = Some(dummyFile), kind = Some("swift:3"))
    }

    assetHelper.withCleaner(wsk.trigger, ruleTriggerName) { (trigger, name) =>
      assetHelper.withCleaner(wsk.rule, ruleName) { (_, _) =>
        // using an asset cleaner on the created trigger name will clean it up at the conclusion of the test
        trigger.create(name)
      }
    }

    // create an action that creates the rule
    val createRuleFile = TestUtils.getTestActionFilename("createRule.swift")
    assetHelper.withCleaner(wsk.action, "ActionThatCreatesRule") { (action, name) =>
      action.create(name, artifact = Some(createRuleFile), kind = Some("swift:3"))
    }

    // invoke the create rule action
    val runCreateRule = wsk.action.invoke(
      "ActionThatCreatesRule",
      Map(
        "triggerName" -> s"/_/$ruleTriggerName".toJson,
        "actionName" -> s"/_/$ruleActionName".toJson,
        "ruleName" -> ruleName.toJson))

    withActivation(wsk.activation, runCreateRule, initialWait = 5 seconds, totalWait = 60 seconds) { activation =>
      // should be successful
      activation.response.success shouldBe true

      // should have a field named "trigger" which is the name of the trigger associated with the rule
      activation.response.result.get.fields("trigger").asJsObject.fields("name") shouldBe ruleTriggerName.toJson

      // should have a field named "action" which is the name of the action associated with the rule
      activation.response.result.get.fields("action").asJsObject.fields("name") shouldBe ruleActionName.toJson
    }
  }
}

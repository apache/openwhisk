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
}

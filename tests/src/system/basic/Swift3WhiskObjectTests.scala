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

package system.basic

import scala.concurrent.duration.DurationInt

import org.junit.runner.RunWith
import org.scalatest.Finders
import org.scalatest.junit.JUnitRunner

import common.TestHelpers
import common.TestUtils
import common.Wsk
import common.WskProps
import common.WskTestHelpers
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.pimpAny

@RunWith(classOf[JUnitRunner])
class Swift3WhiskObjectTests
    extends TestHelpers
    with WskTestHelpers {

    implicit val wskprops = WskProps()
    val wsk = new Wsk()

    behavior of "Swift 3 Whisk backend API"

    it should "allow Swift actions to invoke other actions" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            // use Wsk to create action from dat/actions/invokeAction.swift
            val file = TestUtils.getCatalogFilename("/samples/invoke.swift")
            val actionName = "invokeAction"
            assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
                action.create(name = actionName, artifact = Some(file), kind = Some("swift:3"), parameters = Map("key0" -> "value0".toJson), expectedExitCode =0)
            }

            // invoke the action
            val run = wsk.action.invoke(actionName, Map("key0" -> "value0".toJson))
            withActivation(wsk.activation, run, initialWait = 5 seconds, totalWait = 60 seconds) {
                activation =>
                    val logs = activation.fields("logs").toString

                    logs should include("It is now")
                    logs should not include("Could not parse date of of the response.")
                    logs should not include("Could not invoke date action.")
            }
    }

    it should "allow Swift actions to trigger events" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            // create a trigger
            val triggerName = s"TestTrigger ${System.currentTimeMillis()}"
            assetHelper.withCleaner(wsk.trigger, triggerName) {
                (trigger, _) => trigger.create(triggerName)
            }

            // create an action that fires the trigger
            val file = TestUtils.getCatalogFilename("/samples/trigger.swift")
            val actionName = "ActionThatTriggers"
            assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
                action.create(name = actionName, artifact = Some(file), kind = Some("swift:3"), parameters = Map("triggerName" -> triggerName.toJson), expectedExitCode =0)
            }

            // invoke the action
            val run = wsk.action.invoke(actionName, Map("key0" -> "value0".toJson))
            wsk.activation.pollFor(1, Some(triggerName), retries = 20)

            val lastTwoActivations = wsk.activation.list(limit = Some(2))

            println("Last two activations:")
            println(lastTwoActivations.stdout)

            val activationIds = wsk.activation.ids(lastTwoActivations)

            activationIds.size should be(2)

            val latestActivation = wsk.activation.get(activationIds(0))
            latestActivation.stdout should include(triggerName)

            val previousActivation = wsk.activation.get(activationIds(1))
            previousActivation.stdout should include(actionName)
    }

}

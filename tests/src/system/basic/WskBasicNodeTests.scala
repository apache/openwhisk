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
import org.scalatest.junit.JUnitRunner

import common.JsHelpers
import common.TestHelpers
import common.TestUtils
import common.TestUtils.ANY_ERROR_EXIT
import common.TestUtils.RunResult
import common.Wsk
import common.WskProps
import common.WskTestHelpers
import spray.json._
import spray.json.DefaultJsonProtocol._

@RunWith(classOf[JUnitRunner])
class WskBasicNodeTests
    extends TestHelpers
    with WskTestHelpers
    with JsHelpers {

    implicit val wskprops = WskProps()
    val wsk = new Wsk
    val defaultAction = Some(TestUtils.getTestActionFilename("hello.js"))
    val currentNodeJsDefaultKind = "nodejs:6"

    behavior of "NodeJS runtime"

    it should "Map a kind of nodejs:default to the current default NodeJS runtime" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "usingDefaultNodeAlias"

            assetHelper.withCleaner(wsk.action, name) {
                (action, _) =>
                    action.create(name, defaultAction, kind = Some("nodejs:default"))
            }

            val result = wsk.action.get(name)
            withPrintOnFailure(result) {
                () =>
                    val action = convertRunResultToJsObject(result)
                    action.getFieldPath("exec", "kind") should be(Some(currentNodeJsDefaultKind.toJson))
            }
    }

    it should "Ensure that NodeJS actions can have a non-default entrypoint" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "niamNpmAction"
            val file = Some(TestUtils.getTestActionFilename("niam.js"))

            assetHelper.withCleaner(wsk.action, name) {
                (action, _) =>
                    action.create(name, file, main = Some("niam"))
            }

            withActivation(wsk.activation, wsk.action.invoke(name)) {
                activation =>
                    val response = activation.response
                    response.result.get.fields.get("error") shouldBe empty
                    response.result.get.fields.get("greetings") should be(Some(JsString("Hello from a non-standard entrypoint.")))
            }
    }

    it should "Ensure that zipped actions are encoded and uploaded as NodeJS actions" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "zippedNpmAction"
            val file = Some(TestUtils.getTestActionFilename("zippedaction.zip"))

            assetHelper.withCleaner(wsk.action, name) {
                (action, _) =>
                    action.create(name, file, kind = Some("nodejs:default"))
            }

            withActivation(wsk.activation, wsk.action.invoke(name)) {
                activation =>
                    val response = activation.response
                    response.result.get.fields.get("error") shouldBe empty
                    response.result.get.fields.get("author") shouldBe defined
            }
    }

    it should "Ensure that zipped actions cannot be created without a kind specified" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "zippedNpmActionWithNoKindSpecified"
            val file = Some(TestUtils.getTestActionFilename("zippedaction.zip"))

            val createResult = assetHelper.withCleaner(wsk.action, name, confirmDelete = false) {
                (action, _) =>
                    action.create(name, file, expectedExitCode = ANY_ERROR_EXIT)
            }

            val output = s"${createResult.stdout}\n${createResult.stderr}"

            output should include("kind")
    }

    it should "Ensure that JS actions created with no explicit kind use the current default NodeJS runtime" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "jsWithNoKindSpecified"

            assetHelper.withCleaner(wsk.action, name) {
                (action, _) =>
                    action.create(name, defaultAction)
            }

            val result = wsk.action.get(name)
            withPrintOnFailure(result) {
                () =>
                    val action = convertRunResultToJsObject(result)
                    action.getFieldPath("exec", "kind") should be(Some(currentNodeJsDefaultKind.toJson))
            }
    }

    it should "Ensure that returning an empty rejected Promise results in an errored activation" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "jsEmptyRejectPromise"

            assetHelper.withCleaner(wsk.action, name) {
                (action, _) =>
                    action.create(name, Some(TestUtils.getTestActionFilename("issue-1562.js")))
            }

            withActivation(wsk.activation, wsk.action.invoke(name)) {
                activation =>
                    val response = activation.response
                    response.success should be(false)
                    response.result.get.fields.get("error") shouldBe defined
            }
    }

    it should "Ensure that UTF-8 in supported in source files, input params, logs, and output results" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "unicodeGalore"

            assetHelper.withCleaner(wsk.action, name) {
                (action, _) =>
                    action.create(name, Some(TestUtils.getTestActionFilename("unicode.js")))
            }

            withActivation(wsk.activation, wsk.action.invoke(name, parameters = Map("delimiter" -> JsString("❄")))) {
                activation =>
                    val response = activation.response
                    response.result.get.fields.get("error") shouldBe empty
                    response.result.get.fields.get("winter") should be(Some(JsString("❄ ☃ ❄")))

                    activation.logs.toList.flatten.mkString(" ") should include("❄ ☃ ❄")
            }
    }

    // TODO: remove this tests and its assets when "whisk.js" is removed entirely as it is no longer necessary
    it should "Ensure that whisk.invoke() returns a promise" in withAssetCleaner(wskprops) {
        val expectedDuration = 3.seconds

        (wp, assetHelper) =>
            val asyncName = "ThreeSecondRule"
            val asyncAction = Some(TestUtils.getTestActionFilename("threeSecondRule.js"))

            assetHelper.withCleaner(wsk.action, asyncName) {
                (action, _) =>
                    action.create(asyncName, asyncAction)
            }

            // this action does not supply a 'next' callback to whisk.invoke()
            // and utilizes the returned promise
            val invokeActionName = "invokeAction"
            val invokeAction = Some(TestUtils.getTestActionFilename("invokePromise.js"))

            assetHelper.withCleaner(wsk.action, invokeActionName) {
                (action, _) =>
                    action.create(invokeActionName, invokeAction)
            }

            var start = System.currentTimeMillis()
            val runResolve = wsk.action.invoke(invokeActionName, Map("resolveOrReject" -> "resolve".toJson))
            withActivation(wsk.activation, runResolve) {
                activation =>
                    val result = activation.response.result.get
                    result.fields.get("activationId") shouldBe defined
                    result.fields.get("error") should not be defined
                    result.getFieldPath("result", "message") should be(Some {
                        "Three second rule!".toJson
                    })

                    val duration = System.currentTimeMillis() - start
                    duration should be >= expectedDuration.toMillis
            }

            start = System.currentTimeMillis()
            val runReject = wsk.action.invoke(invokeActionName, Map("resolveOrReject" -> "reject".toJson))
            withActivation(wsk.activation, runReject) {
                activation =>
                    val result = activation.response.result.get
                    result.fields.get("activationId") should not be defined
                    result.getFieldPath("error", "message") should be(Some {
                        "Three second rule!".toJson
                    })

                    val duration = System.currentTimeMillis() - start
                    duration should be >= expectedDuration.toMillis
            }
    }

    // TODO: remove this tests and its assets when "whisk.js" is removed entirely as it is no longer necessary
    it should "Ensure that whisk.invoke() still uses a callback when provided one" in withAssetCleaner(wskprops) {
        val expectedDuration = 3.seconds

        (wp, assetHelper) =>
            val asyncName = "ThreeSecondRule"
            val asyncAction = Some(TestUtils.getTestActionFilename("threeSecondRule.js"))

            assetHelper.withCleaner(wsk.action, asyncName) {
                (action, _) =>
                    action.create(asyncName, asyncAction)
            }

            // this action supplies a 'next' callback to whisk.invoke()
            val invokeActionName = "invokeAction"
            val invokeAction = Some(TestUtils.getTestActionFilename("invokeCallback.js"))

            assetHelper.withCleaner(wsk.action, invokeActionName) {
                (action, _) =>
                    action.create(invokeActionName, invokeAction)
            }

            var start = System.currentTimeMillis()
            val runResolve = wsk.action.invoke(invokeActionName, Map("resolveOrReject" -> "resolve".toJson))
            withActivation(wsk.activation, runResolve) {
                activation =>
                    val result = activation.response.result.get
                    result.fields.get("activationId") shouldBe defined
                    result.fields.get("error") should not be defined
                    result.getFieldPath("result", "message") should be(Some {
                        "Three second rule!".toJson
                    })

                    val duration = System.currentTimeMillis() - start
                    duration should be >= expectedDuration.toMillis
            }

            start = System.currentTimeMillis()
            val runReject = wsk.action.invoke(invokeActionName, Map("resolveOrReject" -> "reject".toJson))
            withActivation(wsk.activation, runReject) {
                activation =>
                    val result = activation.response.result.get
                    result.fields.get("activationId") should not be defined
                    result.getFieldPath("error", "message") should be(Some {
                        "Three second rule!".toJson
                    })

                    val duration = System.currentTimeMillis() - start
                    duration should be >= expectedDuration.toMillis
            }
    }

    // TODO: remove this tests and its assets when "whisk.js" is removed entirely as it is no longer necessary
    it should "Ensure that whisk.trigger() still uses a callback when provided one" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            // this action supplies a 'next' callback to whisk.trigger()
            val nameOfActionThatTriggers = "triggerAction"
            val actionThatTriggers = Some(TestUtils.getTestActionFilename("triggerCallback.js"))
            val triggerName = "UnitTestTrigger-" + System.currentTimeMillis()

            assetHelper.withCleaner(wsk.action, nameOfActionThatTriggers) {
                (action, _) =>
                    action.create(nameOfActionThatTriggers, actionThatTriggers)
            }

            // this is expected to fail this time because we have not yet created the trigger
            val runReject = wsk.action.invoke(nameOfActionThatTriggers, Map("triggerName" -> triggerName.toJson))
            withActivation(wsk.activation, runReject) {
                activation =>
                    activation.response.success shouldBe false
                    activation.response.result.get.fields.get("error") shouldBe defined
            }

            assetHelper.withCleaner(wsk.trigger, triggerName) {
                (trigger, _) =>
                    trigger.create(triggerName)
            }

            // now that we've created the trigger, running the action should succeed
            val runResolve = wsk.action.invoke(nameOfActionThatTriggers, Map("triggerName" -> triggerName.toJson))
            withActivation(wsk.activation, runResolve) {
                activation =>
                    activation.response.success shouldBe true
                    activation.response.result.get.fields.get("activationId") shouldBe defined
            }
    }

    // TODO: remove this tests and its assets when "whisk.js" is removed entirely as it is no longer necessary
    it should "Ensure that whisk.trigger() returns a promise" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            // this action supplies a 'next' callback to whisk.trigger()
            val nameOfActionThatTriggers = "triggerAction"
            val actionThatTriggers = Some(TestUtils.getTestActionFilename("triggerPromise.js"))
            val triggerName = "UnitTestTrigger-" + System.currentTimeMillis()

            assetHelper.withCleaner(wsk.action, nameOfActionThatTriggers) {
                (action, _) =>
                    action.create(nameOfActionThatTriggers, actionThatTriggers)
            }

            // this is expected to fail this time because we have not yet created the trigger
            val runReject = wsk.action.invoke(nameOfActionThatTriggers, Map("triggerName" -> triggerName.toJson))
            withActivation(wsk.activation, runReject) {
                activation =>
                    activation.response.success shouldBe false
                    activation.response.result.get.fields.get("error") shouldBe defined
            }

            assetHelper.withCleaner(wsk.trigger, triggerName) {
                (trigger, _) =>
                    trigger.create(triggerName)
            }

            // now that we've created the trigger, running the action should succeed
            val runResolve = wsk.action.invoke(nameOfActionThatTriggers, Map("triggerName" -> triggerName.toJson))
            withActivation(wsk.activation, runResolve) {
                activation =>
                    activation.response.success shouldBe true
                    activation.response.result.get.fields.get("activationId") shouldBe defined
            }
    }

    def convertRunResultToJsObject(result: RunResult): JsObject = {
        val stdout = result.stdout
        val firstNewline = stdout.indexOf("\n")
        stdout.substring(firstNewline + 1).parseJson.asJsObject
    }
}

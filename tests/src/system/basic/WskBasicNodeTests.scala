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

    def convertRunResultToJsObject(result: RunResult): JsObject = {
        val stdout = result.stdout
        val firstNewline = stdout.indexOf("\n")
        stdout.substring(firstNewline + 1).parseJson.asJsObject
    }
}

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
import common.Wsk
import common.WskProps
import common.WskTestHelpers
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.pimpAny
import spray.json.pimpString
import common.TestUtils.RunResult
import spray.json.JsObject

@RunWith(classOf[JUnitRunner])
class WskBasicNodeTests
        extends TestHelpers
        with WskTestHelpers
        with JsHelpers {

    implicit val wskprops = WskProps()
    val wsk = new Wsk()
    val defaultAction = Some(TestUtils.getCatalogFilename("samples/hello.js"))

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

    it should "Ensure that JS actions created with no explicit kind use the current default NodeJS runtime" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "jsWithNoKindSpecified"
            val file = Some(TestUtils.getCatalogFilename("samples/hello.js"))

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

    def convertRunResultToJsObject(result: RunResult): JsObject = {
        val stdout = result.stdout
        val firstNewline = stdout.indexOf("\n")
        stdout.substring(firstNewline + 1).parseJson.asJsObject
    }
}

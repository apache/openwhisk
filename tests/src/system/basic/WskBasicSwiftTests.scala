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
class WskBasicSwiftTests
    extends TestHelpers
    with WskTestHelpers
    with JsHelpers {

    implicit val wskprops = WskProps()
    val wsk = new Wsk
    val defaultAction = Some(TestUtils.getTestActionFilename("hello.swift"))
    val currentSwiftDefaultKind = "swift:3"

    behavior of "Swift runtime"

    it should "Map a kind of swift:default to the current default swift runtime" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "usingDefaultSwiftAlias"

            assetHelper.withCleaner(wsk.action, name) {
                (action, _) =>
                    action.create(name, defaultAction, kind = Some("swift:default"))
            }

            val result = wsk.action.get(name)
            withPrintOnFailure(result) {
                () =>
                    val action = convertRunResultToJsObject(result)
                    action.getFieldPath("exec", "kind") should be(Some(currentSwiftDefaultKind.toJson))
            }
    }

    def convertRunResultToJsObject(result: RunResult): JsObject = {
        val stdout = result.stdout
        val firstNewline = stdout.indexOf("\n")
        stdout.substring(firstNewline + 1).parseJson.asJsObject
    }
}

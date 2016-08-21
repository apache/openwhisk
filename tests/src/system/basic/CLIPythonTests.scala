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

package system.basic;

import org.junit.runner.RunWith
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import common.TestUtils
import common.Wsk
import common.WskProps
import spray.json._
import spray.json.DefaultJsonProtocol.StringJsonFormat
import common.TestHelpers
import common.WskTestHelpers
import common.WskProps
import common.JsHelpers
import common.WhiskProperties

@RunWith(classOf[JUnitRunner])
class CLIPythonTests
    extends TestHelpers
    with WskTestHelpers
    with JsHelpers
    with Matchers {

    implicit val wskprops = WskProps()
    val wsk = new Wsk(usePythonCLI = false)

    behavior of "Native Python Action"

    it should "invoke an action and get the result" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "basicInvoke"
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) => action.create(name, Some(TestUtils.getCatalogFilename("samples/hello.py")))
            }

            withActivation(wsk.activation, wsk.action.invoke(name, Map("name" -> "Prince".toJson))) {
                _.fields("response").toString should include("Prince")
            }
    }

    it should "invoke an action and confirm expected environment is defined" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "stdenv"
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) => action.create(name, Some(TestUtils.getTestActionFilename("stdenv.py")))
            }

            withActivation(wsk.activation, wsk.action.invoke(name)) {
                activation =>
                    val result = activation.fields("response").asJsObject.fields("result").asJsObject
                    result.fields.get("error") shouldBe empty
                    result.fields.get("auth") shouldBe Some(JsString(WhiskProperties.readAuthKey(WhiskProperties.getAuthFileForTesting)))
                    result.fields.get("edge").toString should include(WhiskProperties.getEdgeHost)
            }
    }

    it should "invoke an invalid action and get error back" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "basicInvoke"
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) => action.create(name, Some(TestUtils.getTestActionFilename("malformed.py")))
            }

            withActivation(wsk.activation, wsk.action.invoke(name)) {
                activation =>
                    activation.getFieldPath("response", "result", "error") shouldBe Some(JsString("The action failed to generate or locate a binary. See logs for details."))
                    activation.fields("logs").toString should { not include ("pythonaction.py") and not include ("flask") }
            }
    }
}

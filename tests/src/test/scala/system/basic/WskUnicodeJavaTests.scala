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
import spray.json._

@RunWith(classOf[JUnitRunner])
class WskUnicodeJavaTests
    extends TestHelpers
    with WskTestHelpers
    with JsHelpers {

    lazy val actionKind = "java"
    lazy val actionSource = "unicode.jar"

    implicit val wskprops = WskProps()
    val wsk = new Wsk


    s"$actionKind action" should "Ensure that UTF-8 in supported in source files, input params, logs, and output results" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = s"unicodeGalore.${actionKind.replace(":", "")}"

            assetHelper.withCleaner(wsk.action, name) {
                (action, _) =>
                    action.create(
                        name,
                        Some(TestUtils.getTestActionFilename(actionSource)),
                        main = if (actionKind == "java") Some("Unicode") else None,
                        kind = Some(actionKind))
            }

            withActivation(wsk.activation, wsk.action.invoke(name, parameters = Map("delimiter" -> JsString("❄")))) {
                activation =>
                    val response = activation.response
                    response.result.get.fields.get("error") shouldBe empty
                    response.result.get.fields.get("winter") should be(Some(JsString("❄ ☃ ❄")))

                    activation.logs.toList.flatten.mkString(" ") should include("❄ ☃ ❄")
            }
    }
}

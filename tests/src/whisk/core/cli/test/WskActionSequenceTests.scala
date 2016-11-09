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

package whisk.core.cli.test

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import common.JsHelpers
import common.TestHelpers
import common.TestUtils
import common.Wsk
import common.WskAdmin
import common.WskProps
import common.WskTestHelpers
import spray.json._

/**
 * Tests creation and retrieval of a sequence action
 */
@RunWith(classOf[JUnitRunner])
class WskActionSequenceTests
    extends TestHelpers
    with JsHelpers
    with WskTestHelpers {

    implicit val wskprops = WskProps()
    val wsk = new Wsk
    val defaultNamespace = wskprops.namespace
    val user = WskAdmin.getUser(wskprops.authKey)

    behavior of "Wsk Action Sequence"

    it should "create, and get an action sequence" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "actionSeq"
            val packageName = "samples"
            val helloName = "hello"
            val catName = "cat"
            val fullHelloActionName = s"/$defaultNamespace/$packageName/$helloName"
            val fullCatActionName = s"/$defaultNamespace/$packageName/$catName"

            assetHelper.withCleaner(wsk.pkg, packageName) {
                (pkg, _) => pkg.create(packageName, shared = Some(true))(wp)
            }

            assetHelper.withCleaner(wsk.action, fullHelloActionName) {
                val file = Some(TestUtils.getTestActionFilename("hello.js"))
                (action, _) => action.create(fullHelloActionName, file, shared = Some(true))(wp)
            }

            assetHelper.withCleaner(wsk.action, fullCatActionName) {
                val file = Some(TestUtils.getTestActionFilename("cat.js"))
                (action, _) => action.create(fullCatActionName, file, shared = Some(true))(wp)
            }

            val artifacts = s"$fullHelloActionName,$fullCatActionName"
            val kindValue = JsString("sequence")
            val compValue = JsArray(
                JsString(resolveDefaultNamespace(fullHelloActionName)),
                JsString(resolveDefaultNamespace(fullCatActionName)))

            assetHelper.withCleaner(wsk.action, name) {
                (action, _) => action.create(name, Some(artifacts), kind = Some("sequence"))
            }

            val stdout = wsk.action.get(name).stdout
            assert(stdout.startsWith(s"ok: got action $name\n"))
            wsk.parseJsonString(stdout).fields("exec").asJsObject.fields("components") shouldBe compValue
            wsk.parseJsonString(stdout).fields("exec").asJsObject.fields("kind") shouldBe kindValue
    }

    private def resolveDefaultNamespace(actionName: String) = actionName.replace("/_/", s"/$user/")
}

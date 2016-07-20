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

import java.io.File

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import common.TestHelpers
import common.TestUtils
import common.TestUtils.REQUEST_ENTITY_TOO_LARGE
import common.WhiskProperties
import common.Wsk
import common.WskProps
import common.WskTestHelpers
import spray.json.DefaultJsonProtocol._
import spray.json.pimpAny
import whisk.core.WhiskConfig
import whisk.core.entity.ByteSize
import java.io.PrintWriter
import java.nio.file.Files
import whisk.core.entity.Exec

@RunWith(classOf[JUnitRunner])
class ActionTests
    extends TestHelpers
    with WskTestHelpers {

    implicit val wskprops = WskProps()
    val wsk = new Wsk()

    val defaultDosAction = TestUtils.getTestActionFilename("timeout.js")
    val allowedActionDuration = 10 seconds

    val testActionsDir = WhiskProperties.getFileRelativeToWhiskHome("tests/dat/actions")
    val actionCodeLimit = Exec.sizeLimit

    behavior of "Actions CLI"

    /**
     * Test a long running action that exceeds the maximum execution time allowed for action
     * by setting the action limit explicitly and attempting to run the action for an additional second.
     */
    it should "error with a proper warning if the action exceeds its time limits" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "TestActionCausingTimeout"
            assetHelper.withCleaner(wsk.action, name, confirmDelete = true) {
                (action, _) => action.create(name, Some(defaultDosAction), timeout = Some(allowedActionDuration))
            }

            val run = wsk.action.invoke(name, Map("payload" -> allowedActionDuration.plus(1 second).toMillis.toJson))
            withActivation(wsk.activation, run) {
                _.fields("response").asJsObject.fields("result").toString should include(
                    s""""error":"action exceeded its time limits of ${allowedActionDuration.toMillis} milliseconds"""")
            }
    }

    /**
     * Test an action that does not exceed the allowed execution timeout of an action.
     */
    it should "succeed on an action staying within its time limits" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "TestActionCausingNoTimeout"
            assetHelper.withCleaner(wsk.action, name, confirmDelete = true) {
                (action, _) => action.create(name, Some(defaultDosAction), timeout = Some(allowedActionDuration))
            }

            val run = wsk.action.invoke(name, Map("payload" -> allowedActionDuration.minus(1 second).toMillis.toJson))
            withActivation(wsk.activation, run) {
                _.fields("response").asJsObject.fields("result").toString should include(
                    """[OK] message terminated successfully""")
            }
    }

    /**
     * Test an action that does not exceed the allowed execution timeout of an action.
     */
    it should "fail on creating an action with exec which is too big" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "TestActionCausingExecTooBig"

            val actionCode = new File(s"$testActionsDir${File.separator}$name.js")
            actionCode.createNewFile()
            val pw = new PrintWriter(actionCode)
            pw.write("a" * ((actionCodeLimit.toBytes / 2) + 1).toInt)
            pw.close

            assetHelper.withCleaner(wsk.action, name, confirmDelete = false) {
                (action, _) =>
                    action.create(name, Some(actionCode.getAbsolutePath), expectedExitCode = REQUEST_ENTITY_TOO_LARGE)
            }

            actionCode.delete
    }
}

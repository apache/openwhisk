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

package whisk.core.limits

import java.io.File
import java.io.PrintWriter

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import common.TestHelpers
import common.TestUtils
import common.TestUtils.TOO_LARGE
import common.WhiskProperties
import common.Wsk
import common.WskProps
import common.WskTestHelpers
import spray.json._
import spray.json.DefaultJsonProtocol._
import spray.json.pimpAny
import whisk.core.entity.Exec
import whisk.core.entity.LogLimit
import whisk.core.entity.size.SizeInt
import whisk.core.entity.size.SizeString
import whisk.http.Messages

@RunWith(classOf[JUnitRunner])
class ActionLimitsTests extends TestHelpers with WskTestHelpers {

    implicit val wskprops = WskProps()
    val wsk = new Wsk()

    val defaultDosAction = TestUtils.getTestActionFilename("timeout.js")
    val allowedActionDuration = 10 seconds

    val testActionsDir = WhiskProperties.getFileRelativeToWhiskHome("tests/dat/actions")
    val actionCodeLimit = Exec.sizeLimit

    behavior of "Action limits"

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
                _.response.result.get.fields("error") shouldBe {
                    Messages.timedoutActivation(allowedActionDuration, false).toJson
                }
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
                _.response.result.get.toString should include(
                    """[OK] message terminated successfully""")

            }
    }

    it should "succeed but truncate logs, if log size exceeds its limit" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val allowedSize = 0 megabytes
            val name = "TestActionCausingExceededLogs"
            assetHelper.withCleaner(wsk.action, name, confirmDelete = true) {
                val actionName = TestUtils.getTestActionFilename("dosLogs.js")
                (action, _) => action.create(name, Some(actionName), logsize = Some(allowedSize))
            }

            val writing = allowedSize + 1.megabytes
            val characters = writing.toBytes / 2 // a character takes 2 bytes

            val run = wsk.action.invoke(name, Map("payload" -> characters.toJson))
            withActivation(wsk.activation, run) { response =>
                val lines = response.logs.get
                lines.last shouldBe LogLimit(allowedSize).truncatedLogMessage
                // dropping 39 characters (timestamp + streamname)
                lines.dropRight(1).map(_.drop(39)).mkString.sizeInBytes should be <= allowedSize
            }
    }

    it should "succeed with one log line" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "TestActionCausingExceededLogs"
            assetHelper.withCleaner(wsk.action, name, confirmDelete = true) {
                val actionName = TestUtils.getTestActionFilename("dosLogs.js")
                (action, _) => action.create(name, Some(actionName))
            }

            val run = wsk.action.invoke(name)
            withActivation(wsk.activation, run) { response =>
                val logs = response.logs.get
                withClue(logs) { logs.size shouldBe 1 }
                logs.head should include("0123456789abcdef")

                response.response.status shouldBe "success"
                response.response.result shouldBe Some(JsObject(
                    "msg" -> 1.toJson))
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
            pw.write("a" * (actionCodeLimit.toBytes + 1).toInt)
            pw.close

            assetHelper.withCleaner(wsk.action, name, confirmDelete = false) {
                (action, _) =>
                    action.create(name, Some(actionCode.getAbsolutePath), expectedExitCode = TOO_LARGE)
            }

            actionCode.delete
    }
}

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

import java.util.Date

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import common.JsHelpers
import common.TestHelpers
import common.TestUtils
import common.Wsk
import common.WskProps
import common.WskTestHelpers
import spray.json.DefaultJsonProtocol.IntJsonFormat
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.DefaultJsonProtocol.arrayFormat
import spray.json.JsArray
import spray.json.JsString
import spray.json.pimpAny

@RunWith(classOf[JUnitRunner])
class WskActionSequenceTests
    extends TestHelpers
    with JsHelpers
    with WskTestHelpers {

    implicit val wskprops = WskProps()
    val wsk = new Wsk(usePythonCLI = false)
    val allowedActionDuration = 120 seconds

    behavior of "Wsk Action Sequence"

    it should "invoke a blocking action and get only the result" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "sequence"

            val actions = Seq("split", "sort", "head", "cat")
            for (actionName <- actions) {
                val file = TestUtils.getTestActionFilename(s"$actionName.js")
                assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
                    action.create(name = actionName, artifact = Some(file))
                }
            }

            assetHelper.withCleaner(wsk.action, name) {
                val sequence = actions.mkString (",")
                (action, _) => action.create(name, Some(sequence), kind = Some("sequence"), timeout = Some(allowedActionDuration))
            }

            val now = "it is now " + new Date()
            val args = Array("what time is it?", now)
            val run = wsk.action.invoke(name, Map("payload" -> args.mkString("\n").toJson))
            withActivation(wsk.activation, run, totalWait = allowedActionDuration) {
                activation =>
                    activation.getFieldPath("response", "result", "payload") shouldBe defined
                    activation.getFieldPath("response", "result", "length") should not be defined
                    activation.getFieldPath("response", "result", "lines") should be(Some {
                        Array(now).toJson
                    })
            }

            // update action sequence
            val newSequence = Seq("split", "sort").mkString (",")
            wsk.action.create(name, Some(newSequence), kind = Some("sequence"), timeout = Some(allowedActionDuration), update = true)
            val secondrun = wsk.action.invoke(name, Map("payload" -> args.mkString("\n").toJson))
            withActivation(wsk.activation, secondrun, totalWait = allowedActionDuration) {
                activation =>
                    activation.getFieldPath("response", "result", "length") should be(Some(2.toJson))
                    activation.getFieldPath("response", "result", "lines") should be(Some {
                        args.sortWith(_.compareTo(_) < 0).toArray.toJson
                    })
            }
    }

    it should "create, and get an action sequence" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "actionSeq"
            val artifacts = "/whisk.system/watson/speechToText,/whisk.system/watson/translate"
            val kindValue = JsString("sequence")
            val compValue = JsArray(
                JsString("/whisk.system/watson/speechToText"),
                JsString("/whisk.system/watson/translate"))

            assetHelper.withCleaner(wsk.action, name) {
                (action, _) => action.create(name, Some(artifacts), kind = Some("sequence"))
            }

            val stdout = wsk.action.get(name).stdout
            assert(stdout.startsWith(s"ok: got action $name\n"))
            wsk.parseJsonString(stdout).fields("exec").asJsObject.fields("components") shouldBe compValue
            wsk.parseJsonString(stdout).fields("exec").asJsObject.fields("kind") shouldBe kindValue
    }

}

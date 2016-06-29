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
import org.scalatest.Finders
import org.scalatest.junit.JUnitRunner

import common.JsHelpers
import common.TestHelpers
import common.TestUtils
import common.Wsk
import common.WskProps
import common.WskTestHelpers
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.DefaultJsonProtocol.arrayFormat
import spray.json.DefaultJsonProtocol.IntJsonFormat
import spray.json.JsArray
import spray.json.JsString
import spray.json.pimpAny

@RunWith(classOf[JUnitRunner])
class WskActionSequenceTests
    extends TestHelpers
    with JsHelpers
    with WskTestHelpers {

    implicit val wskprops = WskProps()
    var usePythonCLI = true
    val wsk = new Wsk(usePythonCLI)
    val defaultAction = Some(TestUtils.getCatalogFilename("samples/hello.js"))
    val allowedActionDuration = 120 seconds

    behavior of "Wsk Action Sequence"

    it should "invoke a blocking action and get only the result" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val pkgname = "my package"
            val name = "sequence action"

            assetHelper.withCleaner(wsk.pkg, pkgname) {
                (pkg, _) => pkg.bind("/whisk.system/util", pkgname)
            }

            assetHelper.withCleaner(wsk.action, name) {
                val sequence = Seq("split", "sort", "head", "cat") map { a => s"$pkgname/$a" } mkString (",")
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
            val newSequence = Seq("split", "sort") map { a => s"$pkgname/$a" } mkString (",")
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
}

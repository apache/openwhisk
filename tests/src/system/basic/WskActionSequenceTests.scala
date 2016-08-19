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
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.DefaultJsonProtocol.arrayFormat
import spray.json.DefaultJsonProtocol.IntJsonFormat
import spray.json.pimpAny

@RunWith(classOf[JUnitRunner])
class WskActionSequenceTests
    extends TestHelpers
    with JsHelpers
    with WskTestHelpers {

    implicit val wskprops = WskProps()
    val wsk = new Wsk(usePythonCLI = true)
    val defaultAction = Some(TestUtils.getCatalogFilename("samples/hello.js"))
    val allowedActionDuration = 120 seconds

    behavior of "Wsk Action Sequence"

    it should "invoke a blocking sequence with two actions and return only the result" in withAssetCleaner(wskprops) {
        (wp, assetHelper) => {
            val seqName = "hello_py_echo_js"

            val helloName = "hello_py"
            assetHelper.withCleaner(wsk.action, helloName) {
                (action, _) => action.create(helloName, Some(TestUtils.getCatalogFilename("samples/hello.py")))
            }

            val now = new Date()
            // call hello without a sequence
            val runHello = wsk.action.invoke(helloName, Map("name" -> now.toString.toJson))
            withActivation(wsk.activation, runHello, totalWait = allowedActionDuration) {
                activation =>
                    println(activation.getFieldPath("response", "result", "greeting"))
                    activation.getFieldPath("response", "result", "greeting") shouldBe defined
                    activation.getFieldPath("response", "result", "greeting").get.toString should include ("Hello " + now + "!")
            }
            val echoName = "echo_js"
            assetHelper.withCleaner(wsk.action, echoName) {
                (action, _) => action.create(echoName, Some(TestUtils.getCatalogFilename("samples/echo.js")))
            }

            assetHelper.withCleaner(wsk.action, seqName) {
                val sequence = Seq(helloName, echoName).mkString(",")
                println(s"sequence of actions: $sequence")
                (action, _) => action.create(seqName, Some(sequence), kind = Some("sequence"), timeout = Some(allowedActionDuration))
            }

            val run = wsk.action.invoke(seqName, Map("name" -> now.toString.toJson))
            withActivation(wsk.activation, run, totalWait = allowedActionDuration) {
                activation =>
                    println(activation.getFieldPath("response", "result", "greeting"))
                    activation.getFieldPath("response", "result", "greeting") shouldBe defined
                    activation.getFieldPath("response", "result", "name") should not be defined
                    activation.getFieldPath("response", "result", "greeting").get.toString should include ("Hello " + now + "!")
            }
        }
    }

    it should "invoke a blocking sequence refering to bound actions in a different package and get only the result" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val pkgname = "my package"
            val name = "sequence action"

            assetHelper.withCleaner(wsk.pkg, pkgname) {
                (pkg, _) => pkg.bind("/whisk.system/util", pkgname)
            }

            assetHelper.withCleaner(wsk.action, name) {
                val sequence = Seq("split", "sort", "head", "cat") map { a => s"$pkgname/$a" } mkString (",")
                println(s"sequence of actions: $sequence")
                (action, _) => action.create(name, Some(sequence), kind = Some("sequence"), timeout = Some(allowedActionDuration))
            }

            // invoke date
            println("INVOKING DATE WITHOUT BINDING")
            val runDate = wsk.action.invoke("/whisk.system/util/date")
            withActivation(wsk.activation, runDate, totalWait = allowedActionDuration) {
                activation =>
                    //activation.getFieldPath("response", "result", "payload") shouldBe defined
                    //activation.getFieldPath("response", "result", "length") should not be defined
                    println("date result" + activation.getFieldPath("response", "result"))
            }

            // invoke date
            println("INVOKING DATE WITH BIDING")
            val runDateBinding = wsk.action.invoke(pkgname +"/date")
            withActivation(wsk.activation, runDateBinding, totalWait = allowedActionDuration) {
                activation =>
                    //activation.getFieldPath("response", "result", "payload") shouldBe defined
                    //activation.getFieldPath("response", "result", "length") should not be defined
                    println("date result" + activation.getFieldPath("response", "result"))
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

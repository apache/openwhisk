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
import spray.json.DefaultJsonProtocol._
import spray.json.JsObject
import spray.json.pimpAny

@RunWith(classOf[JUnitRunner])
class WskActionTests
    extends TestHelpers
    with WskTestHelpers
    with JsHelpers {

    implicit val wskprops = WskProps()
    val wsk = new Wsk

    val testString = "this is a test"
    val testResult = JsObject("count" -> testString.split(" ").length.toJson)
    val guestNamespace = wskprops.namespace

    behavior of "Whisk actions"

    it should "invoke an action with a space in the name" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "hello Async"
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) => action.create(name, Some(TestUtils.getTestActionFilename("helloAsync.js")))
            }

            val run = wsk.action.invoke(name, Map("payload" -> testString.toJson))
            withActivation(wsk.activation, run) {
                activation =>
                    activation.response.status shouldBe "success"
                    activation.response.result shouldBe Some(testResult)
                    activation.logs.get.mkString(" ") should include(testString)
            }
    }

    it should "pass parameters bound on creation-time to the action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "printParams"
            val params = Map(
                "param1" -> "test1",
                "param2" -> "test2")

            assetHelper.withCleaner(wsk.action, name) {
                (action, _) =>
                    action.create(
                        name,
                        Some(TestUtils.getTestActionFilename("printParams.js")),
                        parameters = params.mapValues(_.toJson))
            }

            val invokeParams = Map("payload" -> testString)
            val run = wsk.action.invoke(name, invokeParams.mapValues(_.toJson))
            withActivation(wsk.activation, run) {
                activation =>
                    val logs = activation.logs.get.mkString(" ")

                    (params ++ invokeParams).foreach {
                        case (key, value) =>
                            logs should include(s"params.$key: $value")
                    }
            }
    }

    it should "copy an action and invoke it successfully" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "copied"
            val packageName = "samples"
            val actionName = "wordcount"
            val fullQualifiedName = s"/$guestNamespace/$packageName/$actionName"

            assetHelper.withCleaner(wsk.pkg, packageName) {
                (pkg, _) => pkg.create(packageName, shared = Some(true))
            }

            assetHelper.withCleaner(wsk.action, fullQualifiedName) {
                val file = Some(TestUtils.getTestActionFilename("wc.js"))
                (action, _) => action.create(fullQualifiedName, file, shared = Some(true))
            }

            assetHelper.withCleaner(wsk.action, name) {
                (action, _) => action.create(name, Some(fullQualifiedName), Some("copy"))
            }

            val run = wsk.action.invoke(name, Map("payload" -> testString.toJson))
            withActivation(wsk.activation, run) {
                activation =>
                    activation.response.status shouldBe "success"
                    activation.response.result shouldBe Some(testResult)
                    activation.logs.get.mkString(" ") should include(testString)
            }
    }

    it should "recreate and invoke a new action with different code" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "recreatedAction"
            assetHelper.withCleaner(wsk.action, name, false) {
                (action, _) => action.create(name, Some(TestUtils.getTestActionFilename("wc.js")))
            }

            val run1 = wsk.action.invoke(name, Map("payload" -> testString.toJson))
            withActivation(wsk.activation, run1) {
                activation =>
                    activation.response.status shouldBe "success"
                    activation.logs.get.mkString(" ") should include(s"The message '$testString' has")
            }

            wsk.action.delete(name)
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) => action.create(name, Some(TestUtils.getTestActionFilename("hello.js")))
            }

            val run2 = wsk.action.invoke(name, Map("payload" -> testString.toJson))
            withActivation(wsk.activation, run2) {
                activation =>
                    activation.response.status shouldBe "success"
                    activation.logs.get.mkString(" ") should include(s"hello $testString")
            }
    }

    it should "fail to invoke an action with an empty file" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "empty"
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) => action.create(name, Some(TestUtils.getTestActionFilename("empty.js")))
            }
            val run = wsk.action.invoke(name)
            withActivation(wsk.activation, run) {
                activation =>
                    activation.response.status shouldBe "action developer error"
                    activation.response.result shouldBe Some(JsObject("error" -> "Missing main/no code to execute.".toJson))
            }
    }

    it should "create an action with an empty file" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "empty"
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) => action.create(name, Some(TestUtils.getTestActionFilename("empty.js")))
            }
            val rr = wsk.action.get(name)
            wsk.parseJsonString(rr.stdout).getFieldPath("exec", "code") shouldBe Some(JsString(""))
    }

    it should "blocking invoke nested blocking actions" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "nestedBlockingAction"
            val child = "wc"

            assetHelper.withCleaner(wsk.action, name) {
                (action, _) => action.create(name, Some(TestUtils.getTestActionFilename("wcbin.js")))
            }
            assetHelper.withCleaner(wsk.action, child) {
                (action, _) => action.create(child, Some(TestUtils.getTestActionFilename("wc.js")))
            }

            val run = wsk.action.invoke(name, Map("payload" -> testString.toJson), blocking = true)
            val activation = wsk.parseJsonString(run.stdout).convertTo[CliActivation]

            withClue(s"check failed for activation: $activation") {
                val wordCount = testString.split(" ").length
                activation.response.result.get shouldBe JsObject("binaryCount" -> s"${wordCount.toBinaryString} (base 2)".toJson)
            }
    }

    it should "blocking invoke an asynchronous action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "helloAsync"
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) => action.create(name, Some(TestUtils.getTestActionFilename("helloAsync.js")))
            }

            val run = wsk.action.invoke(name, Map("payload" -> testString.toJson), blocking = true)
            val activation = wsk.parseJsonString(run.stdout).convertTo[CliActivation]

            withClue(s"check failed for activation: $activation") {
                activation.response.status shouldBe "success"
                activation.response.result shouldBe Some(testResult)
                activation.logs shouldBe Some(List())
            }
    }

    it should "return the value of the first synchronous whisk.done()" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "helloSyncDoneTwice"
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) => action.create(name, Some(TestUtils.getTestActionFilename("helloSyncDoneTwice.js")))
            }

            val run = wsk.action.invoke(name, Map("payload" -> testString.toJson))
            withActivation(wsk.activation, run) {
                activation =>
                    activation.response.status shouldBe "success"
                    activation.response.result shouldBe Some(testResult)
                    activation.logs.get.mkString(" ") should include(testString)
            }
    }

    it should "return the value of the first asynchronous whisk.done()" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "helloSyncDoneTwice"
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) => action.create(name, Some(TestUtils.getTestActionFilename("helloAsyncDoneTwice.js")))
            }

            val run = wsk.action.invoke(name, Map("payload" -> testString.toJson))
            withActivation(wsk.activation, run) {
                activation =>
                    activation.response.status shouldBe "success"
                    activation.response.result shouldBe Some(testResult)
                    activation.logs.get.mkString(" ") should include(testString)
            }
    }

    it should "reject an invoke with the wrong parameters set" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val fullQualifiedName = s"/$guestNamespace/samples/helloWorld"
            val payload = "bob"
            val rr = wsk.cli(Seq("action", "invoke", fullQualifiedName, payload), expectedExitCode = TestUtils.ERROR_EXIT)
            rr.stderr should include("Run 'wsk --help' for usage.")
            rr.stderr should include(s"error: Invalid argument(s): $payload")
    }

    it should "not be able to use 'ping' in an action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "ping"
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) => action.create(name, Some(TestUtils.getTestActionFilename("ping.js")))
            }

            val run = wsk.action.invoke(name, Map("payload" -> "google.com".toJson))
            withActivation(wsk.activation, run) {
                activation =>
                    activation.response.result shouldBe Some(JsObject(
                        "stderr" -> "ping: icmp open socket: Operation not permitted\n".toJson,
                        "stdout" -> "".toJson))
            }
    }

    ignore should "support UTF-8 as input and output format" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "utf8Test"
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) => action.create(name, Some(TestUtils.getTestActionFilename("hello.js")))
            }

            val utf8 = "«ταБЬℓσö»: 1<2 & 4+1>³, now 20%€§$ off!"
            val run = wsk.action.invoke(name, Map("payload" -> utf8.toJson))
            withActivation(wsk.activation, run) {
                activation =>
                    activation.response.status shouldBe "success"
                    activation.logs.get.mkString(" ") should include(s"hello $utf8")
            }
    }

}

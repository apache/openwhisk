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

package packages.samples

import java.io.File

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import common.TestHelpers
import common.Wsk
import common.WskProps
import common.WskTestHelpers
import spray.json._
import spray.json.DefaultJsonProtocol._

@RunWith(classOf[JUnitRunner])
class GreetingTests extends TestHelpers
    with WskTestHelpers {

    implicit val wskprops = WskProps()
    val wsk = new Wsk()
    val catalogDir = new File(scala.util.Properties.userDir.toString(), "../packages")
    val greetingAction = "/whisk.system/samples/greeting"

    behavior of "Greeting sample"

    it should "contain stranger from somewhere when using a 'wrong' parameter" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val helloMessage = "Hello, stranger from somewhere!".toJson
            val run = wsk.action.invoke(greetingAction, Map("dummy" -> "dummy".toJson))
            withActivation(wsk.activation, run) {
                activation =>
                    activation.response.success shouldBe true
                    activation.response.result shouldBe Some(JsObject("payload" -> helloMessage.toJson))
            }
    }

    it should "contain the sent name when using the 'name' parameter, defaulting the place to somewhere" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val helloStranger = "Hello, Mork from somewhere!".toJson
            val run = wsk.action.invoke(greetingAction, Map("name" -> "Mork".toJson))
            withActivation(wsk.activation, run) {
                activation =>
                    activation.response.success shouldBe true
                    activation.response.result shouldBe Some(JsObject("payload" -> helloStranger.toJson))
            }
    }

    it should "contain the sent name and place when using 'name' and 'place' parameters" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val helloMessage = "Hello, Mork from Ork!".toJson
            val run = wsk.action.invoke(greetingAction, Map("name" -> "Mork".toJson, "place" -> "Ork".toJson))
            withActivation(wsk.activation, run) {
                activation =>
                    activation.response.success shouldBe true
                    activation.response.result shouldBe Some(JsObject("payload" -> helloMessage.toJson))
            }
    }
}

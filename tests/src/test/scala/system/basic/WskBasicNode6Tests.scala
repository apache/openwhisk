/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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
import common.TestUtils.RunResult
import common.BaseWsk
import common.WskProps
import common.WskTestHelpers
import spray.json._

@RunWith(classOf[JUnitRunner])
abstract class WskBasicNode6Tests extends TestHelpers with WskTestHelpers with JsHelpers {

  implicit val wskprops = WskProps()
  val wsk: BaseWsk
  val defaultAction = Some(TestUtils.getTestActionFilename("hello.js"))
  lazy val currentNodeJsKind = "nodejs:6"

  behavior of "Runtime $currentNodeJsKind"

  it should "Ensure that NodeJS actions can have a non-default entrypoint" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "niamNpmAction"
      val file = Some(TestUtils.getTestActionFilename("niam.js"))

      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, file, main = Some("niam"), kind = Some(currentNodeJsKind))
      }

      withActivation(wsk.activation, wsk.action.invoke(name)) { activation =>
        val response = activation.response
        response.result.get.fields.get("error") shouldBe empty
        response.result.get.fields.get("greetings") should be(Some(JsString("Hello from a non-standard entrypoint.")))
      }
  }

  it should "Ensure that zipped actions are encoded and uploaded as NodeJS actions" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "zippedNpmAction"
      val file = Some(TestUtils.getTestActionFilename("zippedaction.zip"))

      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, file, kind = Some(currentNodeJsKind))
      }

      withActivation(wsk.activation, wsk.action.invoke(name)) { activation =>
        val response = activation.response
        response.result.get.fields.get("error") shouldBe empty
        response.result.get.fields.get("author") shouldBe defined
      }
  }

  it should "Ensure that returning an empty rejected Promise results in an errored activation" in withAssetCleaner(
    wskprops) { (wp, assetHelper) =>
    val name = "jsEmptyRejectPromise"

    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, Some(TestUtils.getTestActionFilename("issue-1562.js")), kind = Some(currentNodeJsKind))
    }

    withActivation(wsk.activation, wsk.action.invoke(name)) { activation =>
      val response = activation.response
      response.success should be(false)
      response.result.get.fields.get("error") shouldBe defined
    }
  }

  def convertRunResultToJsObject(result: RunResult): JsObject = {
    val stdout = result.stdout
    val firstNewline = stdout.indexOf("\n")
    stdout.substring(firstNewline + 1).parseJson.asJsObject
  }
}

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
import common.Wsk
import common.WskProps
import common.WskTestHelpers
import spray.json.pimpString
import spray.json.JsString
import common.TestUtils.RunResult
import spray.json.JsObject

@RunWith(classOf[JUnitRunner])
class WskBasicSwift3Tests extends TestHelpers with WskTestHelpers with JsHelpers {

  implicit val wskprops = WskProps()
  val wsk = new Wsk
  val defaultAction = Some(TestUtils.getTestActionFilename("hello.swift"))
  lazy val currentSwiftDefaultKind = "swift:3"

  behavior of "Swift runtime"

  it should "Ensure that Swift actions can have a non-default entrypoint" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "niamSwiftAction"
      val file = Some(TestUtils.getTestActionFilename("niam.swift"))

      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, file, main = Some("niam"))
      }

      withActivation(wsk.activation, wsk.action.invoke(name)) { activation =>
        val response = activation.response
        response.result.get.fields.get("error") shouldBe empty
        response.result.get.fields.get("greetings") should be(Some(JsString("Hello from a non-standard entrypoint.")))
      }
  }

  def convertRunResultToJsObject(result: RunResult): JsObject = {
    val stdout = result.stdout
    val firstNewline = stdout.indexOf("\n")
    stdout.substring(firstNewline + 1).parseJson.asJsObject
  }
}

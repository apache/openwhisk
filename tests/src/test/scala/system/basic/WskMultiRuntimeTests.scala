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
import common.JsHelpers
import common.TestHelpers
import common.TestUtils
import common.WskActorSystem
import common.WskOperations
import common.WskProps
import common.WskTestHelpers
import common.rest.WskRestOperations
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import spray.json._
import spray.json.DefaultJsonProtocol._

@RunWith(classOf[JUnitRunner])
class WskMultiRuntimeTests extends TestHelpers with WskTestHelpers with JsHelpers with WskActorSystem {

  implicit val wskprops = WskProps()
  // wsk must have type WskOperations so that tests using CLI (class Wsk)
  // instead of REST (WskRestOperations) still work.
  val wsk: WskOperations = new WskRestOperations
  val testString = "this is a test"

  it should "update an action with different language and check preserving params" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "updatedAction"

      assetHelper.withCleaner(wsk.action, name, false) { (action, _) =>
        wsk.action.create(
          name,
          Some(TestUtils.getTestActionFilename("hello.js")),
          parameters = Map("name" -> testString.toJson)) //unused in the first function
      }

      wsk.action.create(name, Some(TestUtils.getTestActionFilename("hello.py")), update = true)

      val run = wsk.action.invoke(name)
      withActivation(wsk.activation, run) { activation =>
        activation.response.status shouldBe "success"
        activation.logs.get.mkString(" ") should include(s"Hello $testString")
      }
  }
}

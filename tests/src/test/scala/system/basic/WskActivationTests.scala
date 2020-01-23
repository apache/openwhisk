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

import common.rest.WskRestOperations
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import common._

import spray.json._
import spray.json.DefaultJsonProtocol._

@RunWith(classOf[JUnitRunner])
class WskActivationTests extends TestHelpers with WskTestHelpers with WskActorSystem {

  implicit val wskprops = WskProps()
  val wsk: WskOperations = new WskRestOperations

  behavior of "Whisk activations"

  it should "fetch result using activation result API" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "hello"
    val expectedResult = JsObject(
      "result" -> JsObject("payload" -> "hello, undefined!".toJson),
      "success" -> true.toJson,
      "status" -> "success".toJson)

    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, Some(TestUtils.getTestActionFilename("hello.js")))
    }

    withActivation(wsk.activation, wsk.action.invoke(name)) { activation =>
      val result = wsk.activation.result(Some(activation.activationId)).stdout.parseJson.asJsObject
      //Remove size from comparison as its exact value may vary
      val resultWithoutSize = JsObject(result.fields - "size")
      resultWithoutSize shouldBe expectedResult
    }
  }
}

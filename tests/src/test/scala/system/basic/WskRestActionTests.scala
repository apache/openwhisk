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

import common.rest.WskRestOperations
import common.TestUtils

import spray.json._

@RunWith(classOf[JUnitRunner])
class WskRestActionTests extends WskActionTests {
  override val wsk: WskRestOperations = new WskRestOperations

  it should "create an action with an empty file" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "empty"
    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, Some(TestUtils.getTestActionFilename("empty.js")))
    }
    val rr = wsk.action.get(name)
    wsk.parseJsonString(rr.stdout).getFieldPath("exec", "code") shouldBe Some(JsString(""))
  }
}

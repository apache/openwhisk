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

package whisk.core.cli.test

import scala.concurrent.duration.DurationInt
import org.junit.runner.RunWith
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import common.TestHelpers
import common.TestUtils
import common.rest.WskRest
import common.WskProps
import common.WskTestHelpers
import spray.json.DefaultJsonProtocol._
import spray.json._

@RunWith(classOf[JUnitRunner])
class Swift311Tests extends TestHelpers with WskTestHelpers with Matchers {

  implicit val wskprops = WskProps()
  val wsk = new WskRest
  val activationPollDuration = 2.minutes
  val defaultJsAction = Some(TestUtils.getTestActionFilename("hello.js"))

  lazy val runtimeContainer = "swift:3.1.1"

  behavior of "Swift Actions"

  /**
   * Test the Swift "hello world" demo sequence
   */
  it should "invoke a swift action" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "helloSwift"
    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, Some(TestUtils.getTestActionFilename("hello.swift")), kind = Some(runtimeContainer))
    }

    withActivation(wsk.activation, wsk.action.invoke(name), totalWait = activationPollDuration) {
      _.response.result.get.toString should include("Hello stranger!")
    }

    withActivation(
      wsk.activation,
      wsk.action.invoke(name, Map("name" -> "Sir".toJson)),
      totalWait = activationPollDuration) {
      _.response.result.get.toString should include("Hello Sir!")
    }
  }

}

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

import common.{ActivationResult, BaseWsk, JsHelpers, TestHelpers, TestUtils, WskProps, WskTestHelpers}

import whisk.utils.retry

import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
abstract class WskActivationTests extends TestHelpers with WskTestHelpers with JsHelpers {
  implicit val wskprops = WskProps()

  val wsk: BaseWsk

  behavior of "Whisk activations"

  it should "get logs from activtion API" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "logFetch"

    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, Some(TestUtils.getTestActionFilename("log.js")))
    }

    val run = wsk.action.invoke(name, blocking = true)
    val activation = wsk.parseJsonString(run.stdout).convertTo[ActivationResult]

    retry({
      val logs = wsk.activation.logs(Some(activation.activationId)).stdout

      logs should include regex ("\\d+-\\d+-\\d+T\\d+:\\d+:\\d+.\\d+Z stdout: this is stdout")
      logs should include regex ("\\d+-\\d+-\\d+T\\d+:\\d+:\\d+.\\d+Z stderr: this is stderr")
    }, 10, Some(1.second))
  }
}

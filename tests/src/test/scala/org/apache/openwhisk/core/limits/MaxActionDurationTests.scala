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

package org.apache.openwhisk.core.limits

import java.io.File

import scala.concurrent.duration.DurationInt
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import common.{ConcurrencyHelpers, TestHelpers, TestUtils, WskActorSystem, WskProps, WskTestHelpers}
import common.rest.WskRestOperations
import org.apache.openwhisk.core.entity._
import spray.json.DefaultJsonProtocol._
import spray.json._
import org.apache.openwhisk.http.Messages
import org.apache.openwhisk.core.entity.TimeLimit
import org.scalatest.tagobjects.Slow

/**
 * Tests for action duration limits. These tests require a deployed backend.
 */
@RunWith(classOf[JUnitRunner])
class MaxActionDurationTests extends TestHelpers with WskTestHelpers with WskActorSystem with ConcurrencyHelpers {

  implicit val wskprops = WskProps()
  val wsk = new WskRestOperations

  /**
   * Purpose of the following integration test is to verify that the action proxy
   * supports the configured maximum action time limit and does not interrupt a
   * running action before the invoker does.
   *
   * Action proxies have to run actions potentially endlessly. It's the invoker's
   * duty to enforce action time limits.
   *
   * Background: in the past, the Node.js action proxy terminated an action
   * before it actually reached its maximum action time limit.
   *
   * Swift is not tested because it uses the same action proxy as Python.
   *
   * ATTENTION: this test runs for at least TimeLimit.MAX_DURATION + 1 minute.
   * With default settings, this is around 6 minutes.
   */
  "node-, python, and java-action" should s"run up to the max allowed duration (${TimeLimit.MAX_DURATION})" taggedAs (Slow) in withAssetCleaner(
    wskprops) { (wp, assetHelper) =>
    // When you add more runtimes, keep in mind, how many actions can be processed in parallel by the Invokers!
    val runtimes = Map("node" -> "helloDeadline.js", "python" -> "sleep.py", "java" -> "sleep.jar")
      .filter {
        case (_, name) =>
          new File(TestUtils.getTestActionFilename(name)).exists()
      }

    concurrently(runtimes.toSeq, TimeLimit.MAX_DURATION + 2.minutes) {
      case (k, name) =>
        println(s"Testing action kind '${k}' with action '${name}'")
        assetHelper.withCleaner(wsk.action, name) { (action, _) =>
          val main = if (k == "java") Some("Sleep") else None
          action.create(
            name,
            Some(TestUtils.getTestActionFilename(name)),
            timeout = Some(TimeLimit.MAX_DURATION),
            main = main)
        }

        val run = wsk.action.invoke(
          name,
          Map("forceHang" -> true.toJson, "sleepTimeInMs" -> (TimeLimit.MAX_DURATION + 30.seconds).toMillis.toJson))

        withActivation(
          wsk.activation,
          run,
          initialWait = 1.minute,
          pollPeriod = 1.minute,
          totalWait = TimeLimit.MAX_DURATION + 2.minutes) { activation =>
          withClue("Activation result not as expected:") {
            activation.response.status shouldBe ActivationResponse.messageForCode(ActivationResponse.DeveloperError)
            activation.response.result shouldBe Some(
              JsObject("error" -> Messages.timedoutActivation(TimeLimit.MAX_DURATION, init = false).toJson))
            activation.duration.toInt should be >= TimeLimit.MAX_DURATION.toMillis.toInt
          }
        }
    }
  }
}

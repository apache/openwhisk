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

package whisk.core.limits

import scala.concurrent.duration.DurationInt

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import common.TestHelpers
import common.TestUtils
import common.Wsk
import common.WskProps
import common.WskTestHelpers
import whisk.core.entity._
import spray.json.DefaultJsonProtocol._
import spray.json._
import whisk.http.Messages
import whisk.core.entity.TimeLimit

/**
 * Tests for action duration limits. These tests require a deployed backend.
 */
@RunWith(classOf[JUnitRunner])
class MaxActionDurationTests
    extends TestHelpers
    with WskTestHelpers {

    implicit val wskprops = WskProps()
    val wsk = new Wsk

    // swift is not tested, because it uses the same proxy like python
    "node-, python, and java-action" should "run up to the max allowed duration" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>

            // When you add more runtimes, keep in mind, how many actions can be processed in parallel by the Invokers!
            Map("node" -> "helloDeadline.js", "python" -> "timedout.py", "java" -> "timedout.jar").par.map {
                case (k, name) =>
                    assetHelper.withCleaner(wsk.action, name) {
                        if (k == "java") {
                            (action, _) => action.create(name, Some(TestUtils.getTestActionFilename(name)), timeout = Some(TimeLimit.MAX_DURATION), main = Some("TimedOut"))
                        } else {
                            (action, _) => action.create(name, Some(TestUtils.getTestActionFilename(name)), timeout = Some(TimeLimit.MAX_DURATION))
                        }
                    }

                    val run = wsk.action.invoke(name, Map("forceHang" -> true.toJson))
                    withActivation(wsk.activation, run, initialWait = 1.minute, pollPeriod = 1.minute, totalWait = TimeLimit.MAX_DURATION + 1.minute) {
                        activation =>
                            activation.response.status shouldBe ActivationResponse.messageForCode(ActivationResponse.ApplicationError)
                            activation.response.result shouldBe Some(JsObject("error" -> Messages.timedoutActivation(TimeLimit.MAX_DURATION, false).toJson))
                            activation.duration.toInt should be >= TimeLimit.MAX_DURATION.toMillis.toInt

                    }
            }
    }

}

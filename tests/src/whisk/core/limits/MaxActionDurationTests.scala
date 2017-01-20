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

    Map("node" -> "helloDeadline.js", "python" -> "timedout.py").foreach {
        case (k, name) =>
            s"$k action" should "run up to the max allowed duration" in withAssetCleaner(wskprops) {
                (wp, assetHelper) =>
                    assetHelper.withCleaner(wsk.action, name) {
                        (action, _) => action.create(name, Some(TestUtils.getTestActionFilename(name)), timeout = Some(TimeLimit.MAX_DURATION))
                    }

                    val run = wsk.action.invoke(name)
                    withActivation(wsk.activation, run, initialWait = 1.minute, pollPeriod = 1.minute, totalWait = TimeLimit.MAX_DURATION + 1.minute) {
                        activation =>
                            activation.response.status shouldBe ActivationResponse.messageForCode(ActivationResponse.ApplicationError)
                            activation.response.result shouldBe Some(JsObject("error" -> Messages.timedoutActivation(TimeLimit.MAX_DURATION, false).toJson))
                            activation.duration.toInt.milliseconds should be >= TimeLimit.MAX_DURATION
                    }
            }
    }

}

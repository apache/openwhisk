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

package system.basic;

import java.time.Instant

import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.MILLISECONDS
import scala.language.postfixOps

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import common.TestHelpers
import common.TestUtils
import common.Wsk
import common.WskProps
import common.WskTestHelpers
import spray.json.DefaultJsonProtocol.IntJsonFormat
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.pimpAny

/**
 * Tests of the text console
 */
@RunWith(classOf[JUnitRunner])
class WskConsoleTests extends TestHelpers with WskTestHelpers {

  implicit val wskprops = WskProps()
  val wsk = new Wsk
  val guestNamespace = wskprops.namespace

  /**
   * Append the current timestamp in ms
   */
  def withTimestamp(text: String) = s"${text}-${System.currentTimeMillis}"

  behavior of "Wsk Activation Console"

  it should "show an activation log message for hello world" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val packageName = withTimestamp("samples")
    val actionName = withTimestamp("helloWorld")
    val fullActionName = s"/$guestNamespace/$packageName/$actionName"
    assetHelper.withCleaner(wsk.pkg, packageName) { (pkg, _) =>
      pkg.create(packageName, shared = Some(true))
    }

    assetHelper.withCleaner(wsk.action, fullActionName) { (action, _) =>
      action.create(fullActionName, Some(TestUtils.getTestActionFilename("hello.js")))
    }

    // Some contingency to make query more robust
    // Account for time differences between controller and invoker
    val start = Instant.now.minusSeconds(5)
    val payload = new String("from the console!".getBytes, "UTF-8")
    val run = wsk.action.invoke(fullActionName, Map("payload" -> payload.toJson))
    withActivation(wsk.activation, run, totalWait = 30 seconds) { activation =>
      val duration = Duration(Instant.now.minusMillis(start.toEpochMilli).toEpochMilli, MILLISECONDS)
      val pollTime = 10 seconds
      // since: poll for activations since specified number of seconds ago (relative)
      val console = wsk.activation.console(pollTime, since = Some(duration))
      withClue(
        s"Poll for ${pollTime.toSeconds} seconds since ${duration.toSeconds} seconds did not return expected result:") {
        console.stdout should include(payload)
      }
    }
  }

  it should "show repeated activations" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = withTimestamp("countdown")
    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, Some(TestUtils.getTestActionFilename("countdown.js")))
    }

    val count = 3
    // Some contingency to make query more robust
    // Account for time differences between controller and invoker
    val start = Instant.now.minusSeconds(5)
    val run = wsk.action.invoke(name, Map("n" -> count.toJson))
    withActivation(wsk.activation, run) { activation =>
      // Time recorded by invoker, some contingency to make query more robust
      val queryTime = activation.start.minusMillis(500)
      // since: poll for activations since specified point in time (absolute)
      val activations = wsk.activation.pollFor(N = 4, Some(name), since = Some(queryTime), retries = 80).length
      withClue(
        s"expected activations of action '${name}' since ${queryTime.toString} / initial activation ${activation.activationId}:") {
        activations should be(count + 1)
      }
      val duration = Duration(Instant.now.minusMillis(start.toEpochMilli).toEpochMilli, MILLISECONDS)
      val pollTime = 10 seconds
      // since: poll for activations since specified number of seconds ago (relative)
      val console = wsk.activation.console(pollTime, since = Some(duration))
      withClue(
        s"Poll for ${pollTime.toSeconds} seconds since ${duration.toSeconds} seconds did not return expected result:") {
        console.stdout should include("Happy New Year")
      }
    }
  }

}

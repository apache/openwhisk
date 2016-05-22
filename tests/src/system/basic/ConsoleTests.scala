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

package system.basic;

import java.time.Clock
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
class ConsoleTests
    extends TestHelpers
    with WskTestHelpers {

    implicit val wskprops = WskProps()
    val wsk = new Wsk()

    behavior of "Wsk Activation Console"

    it should "show an activation log message for hello world" in {
        val duration = Some(30 seconds)
        val payload = new String("from the console!".getBytes, "UTF-8")
        val run = wsk.action.invoke("/whisk.system/samples/helloWorld", Map("payload" -> payload.toJson))
        withActivation(wsk.activation, run, totalWait = duration.get) {
            activation =>
                val console = wsk.activation.console(10 seconds, since = duration)
                println(console.stdout)
                console.stdout should include(payload)
        }
    }

    it should "show repeated activations" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "countdown"
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) => action.create(name, Some(TestUtils.getCatalogFilename("samples/countdown.js")))
            }

            val start = Instant.now(Clock.systemUTC())
            val run = wsk.action.invoke(name, Map("n" -> 3.toJson))
            withActivation(wsk.activation, run) {
                activation =>
                    val activations = wsk.activation.pollFor(N = 4, Some(name), since = Some(start), retries = 80).length
                    withClue(s"expected activations:") {
                        activations should be(4)
                    }
                    val duration = Duration(Instant.now(Clock.systemUTC()).toEpochMilli - start.toEpochMilli, MILLISECONDS)
                    val console = wsk.activation.console(10 seconds, since = Some(duration))
                    console.stdout should include("Happy New Year")
            }
    }

}

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
import common.rest.WskRest
import common.WskProps
import common.WskTestHelpers
import spray.json.DefaultJsonProtocol.IntJsonFormat
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.pimpAny

/**
 * Tests of the text console
 */
@RunWith(classOf[JUnitRunner])
class WskRestConsoleTests
    extends TestHelpers
    with WskTestHelpers {

    implicit val wskProps = WskProps()
    val wskRest = new WskRest
    val guestNamespace = wskProps.namespace

    behavior of "Wsk Activation Console"

    it should "show an activation log message for hello world" in withAssetCleaner(wskProps) {
        (wp, assetHelper) =>
            val packageName = "samples"
            val actionName = "helloWorld"
            val fullActionName = s"/$guestNamespace/$packageName/$actionName"
            assetHelper.withCleaner(wskRest.pkg, packageName) {
                (pkg, _) => pkg.create(packageName, shared = Some(true))
            }

            assetHelper.withCleaner(wskRest.action, fullActionName) {
                (action, _) => action.create(fullActionName, Some(TestUtils.getTestActionFilename("hello.js")))
            }

            val duration = Some(30 seconds)
            val payload = new String("from the console!".getBytes, "UTF-8")
            val run = wskRest.action.invoke(fullActionName, Map("payload" -> payload.toJson))
            withActivation(wskRest.activation, run, totalWait = duration.get) {
                activation =>
                    val console = wskRest.activation.console(10 seconds, since = duration)
                    println(console.respData)
                    console.respData should include(payload)
            }
    }

    it should "show repeated activations" in withAssetCleaner(wskProps) {
        (wp, assetHelper) =>
            val name = "countdown"
            assetHelper.withCleaner(wskRest.action, name) {
                (action, _) => action.create(name, Some(TestUtils.getTestActionFilename("countdown.js")))
            }

            val start = Instant.now(Clock.systemUTC())
            val run = wskRest.action.invoke(name, Map("n" -> 3.toJson))
            withActivation(wskRest.activation, run) {
                activation =>
                    val activations = wskRest.activation.pollFor(N = 4, Some(name), since = Some(start), retries = 80).length
                    withClue(s"expected activations:") {
                        activations should be(4)
                    }
                    val duration = Duration(Instant.now(Clock.systemUTC()).toEpochMilli - start.toEpochMilli, MILLISECONDS)
                    val console = wskRest.activation.console(10 seconds, since = Some(duration))
                    console.respData should include("Happy New Year")
            }
    }

}

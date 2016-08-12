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

package system.basic

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import common.TestHelpers
import common.TestUtils
import common.WskTestHelpers
import common.WskProps
import common.Wsk

import org.junit.runner.RunWith
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import spray.json.JsString

@RunWith(classOf[JUnitRunner])
class CLIJavaTests
    extends TestHelpers
    with WskTestHelpers
    with Matchers {

    implicit val wskprops = WskProps()
    val wsk = new Wsk(usePythonCLI = false)
    val expectedDuration = 120 seconds
    val activationPollDuration = 60 seconds

    behavior of "Java Actions"

    /**
     * Test the Java "hello world" demo sequence
     */
    it should "invoke a java action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "helloJava"
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) => action.create(name, Some(TestUtils.getCatalogFilename("samples/helloJava/build/libs/helloJava.jar")))
            }

            val start = System.currentTimeMillis()
            withActivation(wsk.activation, wsk.action.invoke(name), totalWait = activationPollDuration) {
                _.fields("response").toString should include("Hello stranger!")
            }

            withActivation(wsk.activation, wsk.action.invoke(name, Map("name" -> JsString("Sir"))), totalWait = activationPollDuration) {
                _.fields("response").toString should include("Hello Sir!")
            }

            withClue("Test duration exceeds expectation (ms)") {
                val duration = System.currentTimeMillis() - start
                duration should be <= expectedDuration.toMillis
            }
    }
}

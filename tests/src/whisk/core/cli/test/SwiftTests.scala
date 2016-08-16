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

package whisk.core.cli.test

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import org.junit.runner.RunWith
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import common.TestHelpers
import common.TestUtils
import common.Wsk
import common.WskProps
import common.WskTestHelpers
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.pimpAny

@RunWith(classOf[JUnitRunner])
class SwiftTests
    extends TestHelpers
    with WskTestHelpers
    with Matchers {

    implicit val wskprops = WskProps()
    val wsk = new Wsk(usePythonCLI = false)
    val expectedDuration = 30 seconds
    val activationPollDuration = 60 seconds

    behavior of "Swift Actions"

    /**
     * Test the Swift "hello world" demo sequence
     */
    it should "invoke a swift action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "helloSwift"
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) => action.create(name, Some(TestUtils.getCatalogFilename("samples/hello.swift")))
            }

            val start = System.currentTimeMillis()
            withActivation(wsk.activation, wsk.action.invoke(name), totalWait = activationPollDuration) {
                _.fields("response").toString should include("Hello stranger!")
            }

            withActivation(wsk.activation, wsk.action.invoke(name, Map("name" -> "Sir".toJson)), totalWait = activationPollDuration) {
                _.fields("response").toString should include("Hello Sir!")
            }

            withClue("Test duration exceeds expectation (ms)") {
                val duration = System.currentTimeMillis() - start
                duration should be <= expectedDuration.toMillis
            }
    }

    /**
     * Test the Swift 3 example
     *
     * It is ignored because Swift3 is experimental. The test is failed sometimes and breaks the CI pipeline. This was agreed.
     */
    ignore should "invoke a swift:3 action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "helloSwift3"
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) =>
                    action.create(
                        name,
                        Some(TestUtils.getCatalogFilename("samples/httpGet.swift")),
                        kind = Some("swift:3"))
            }

            withActivation(wsk.activation, wsk.action.invoke(name), totalWait = activationPollDuration) {
                activation =>
                    activation.fields("response").toString should include(""""url":"https://httpbin.org/get"""")
                    activation.fields("response").toString should not include ("Error")
            }
    }
}

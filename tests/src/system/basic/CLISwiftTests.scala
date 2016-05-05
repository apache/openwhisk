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

import java.io.File
import scala.collection.mutable.ListBuffer
import org.apache.commons.io.FileUtils
import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.ParallelTestExecution
import org.scalatest.TestData
import org.scalatest.junit.JUnitRunner
import common.DeleteFromCollection
import common.RunWskAdminCmd
import common.RunWskCmd
import common.TestUtils
import common.TestUtils._
import common.Wsk
import common.WskAction
import common.WskProps
import spray.json._
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.PimpedAny
import common.TestHelpers
import common.WskTestHelpers
import common.WskProps

@RunWith(classOf[JUnitRunner])
class CLISwiftTests
    extends TestHelpers
    with WskTestHelpers
    with Matchers {

    implicit val wskprops = WskProps()
    val wsk = new Wsk()
    val expectedDuration = 30 * 1000

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
            wsk.action.invoke(name, blocking = true, result = true)
                .stdout should include("Hello stranger!")

            wsk.action.invoke(name, Map("name" -> "Sir".toJson), blocking = true, result = true)
                .stdout should include("Hello Sir!")

            withClue("Test duration exceeds expectation (ms)") {
                val duration = System.currentTimeMillis() - start
                duration should be <= expectedDuration.toLong
            }
    }

    /**
     * Test the Swift 3 example
     */
    it should "invoke a swift:3 action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "helloSwift3"
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) => action.create(name, Some(TestUtils.getCatalogFilename("samples/httpGet.swift")), kind = Some("swift:3"))
            }

            val stdout = wsk.action.invoke(name, blocking = true, result = true).stdout
            stdout should include(""""url": "https://httpbin.org/get"""")
            stdout should not include("Error")
    }
}

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

package packages

import common._
import org.junit.runner.RunWith
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import spray.json._
import spray.json.DefaultJsonProtocol.StringJsonFormat

@RunWith(classOf[JUnitRunner])
class UtilsTests extends TestHelpers with WskTestHelpers with Matchers {

    implicit val wskprops = WskProps()
    var usePythonCLI = true
    val wsk = new Wsk(usePythonCLI)
    val lines = JsArray(JsString("seven"), JsString("eight"), JsString("nine"))

    behavior of "Util Actions"

    /**
      * Test the Node.js "cat" action
      */
    it should "concatenate an array of strings using the node.js cat action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val wsk = new Wsk(usePythonCLI)

            withActivation(wsk.activation, wsk.action.invoke("/whisk.system/util/cat", Map("lines" -> lines))) {
                _.fields("response").toString should include(""""payload":"seven\neight\nnine"""")
            }
    }

    /**
      * Test the "cat" action using Node.js 6
      */
    it should "concatenate an array of strings using the cat action on node.js 6" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val wsk = new Wsk(usePythonCLI)

            val file = TestUtils.getCatalogFilename("utils/cat.js")
            val actionName = "catNodejs6"

            assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
                action.create(name = actionName, artifact = Some(file), kind = Some("nodejs:6"))
            }

            withActivation(wsk.activation, wsk.action.invoke(actionName, Map("lines" -> lines))) {
                _.fields("response").toString should include(""""payload":"seven\neight\nnine"""")
            }
    }

    /**
      * Test the Swift "cat" action using Swift 3
      */
    it should "concatenate an array of strings using the swift cat action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val wsk = new Wsk(usePythonCLI)

            val file = TestUtils.getCatalogFilename("utils/cat.swift")
            val actionName = "catSwift3"

            assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
                action.create(name = actionName, artifact = Some(file), kind = Some("swift:3"))
            }

            withActivation(wsk.activation, wsk.action.invoke(actionName, Map("lines" -> lines))) {
                _.fields("response").toString should include(""""payload":"seven\neight\nnine\n"""")
            }

    }

    /**
      * Test the Node.js "split" action
      */
    it should "split a string into an array of strings using the node.js split action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val wsk = new Wsk(usePythonCLI)

            withActivation(wsk.activation, wsk.action.invoke("/whisk.system/util/split", Map("payload" -> "seven,eight,nine".toJson, "separator" -> ",".toJson))) {
                _.fields("response").toString should include (""""lines":["seven","eight","nine"]""")
            }
    }

    /**
      * Test the "split" action using Node.js 6
      */
    it should "split a string into an array of strings using the split action on nodejs 6" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val wsk = new Wsk(usePythonCLI)

            val file = TestUtils.getCatalogFilename("utils/split.js")
            val actionName = "splitNodejs6"

            assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
                action.create(name = actionName, artifact = Some(file), kind = Some("nodejs:6"))
            }

            withActivation(wsk.activation, wsk.action.invoke(actionName, Map("payload" -> "seven,eight,nine".toJson, "separator" -> ",".toJson))) {
                _.fields("response").toString should include (""""lines":["seven","eight","nine"]""")
            }
    }

    /**
      * Test the Swift "split" action using Swift 3
      */
    it should "split a string into an array of strings using the swift split action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val wsk = new Wsk(usePythonCLI)

            val file = TestUtils.getCatalogFilename("utils/split.swift")
            val actionName = "splitSwift3"

            assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
                action.create(name = actionName, artifact = Some(file), kind = Some("swift:3"))
            }

            withActivation(wsk.activation, wsk.action.invoke(actionName, Map("payload" -> "seven,eight,nine".toJson, "separator" -> ",".toJson))) {
                _.fields("response").toString should include (""""lines":["seven","eight","nine"]""")
            }
    }

    /**
      * Test the Node.js "head" action
      */
    it should "extract first n elements of an array of strings using the node.js head action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val wsk = new Wsk(usePythonCLI)

            withActivation(wsk.activation, wsk.action.invoke("/whisk.system/util/head", Map("lines" -> lines, "num" -> JsNumber(2)))) {
                _.fields("response").toString should include(""""lines":["seven","eight"]""")
            }
    }

    /**
      * Test the "head" action using Node.js 6
      */
    it should "extract first n elements of an array of strings using the head action on nodejs 6" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val wsk = new Wsk(usePythonCLI)

            val file = TestUtils.getCatalogFilename("utils/head.js")
            val actionName = "headNodejs6"

            assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
                action.create(name = actionName, artifact = Some(file), kind = Some("nodejs:6"))
            }

            withActivation(wsk.activation, wsk.action.invoke(actionName, Map("lines" -> lines, "num" -> JsNumber(2)))) {
                _.fields("response").toString should include(""""lines":["seven","eight"]""")
            }
    }

    /**
      * Test the Swift "head" action using Swift 3
      */
    it should "extract first n elements of an array of strings using the swift head action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val wsk = new Wsk(usePythonCLI)

            val file = TestUtils.getCatalogFilename("utils/head.swift")
            val actionName = "headSwift3"

            assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
                action.create(name = actionName, artifact = Some(file), kind = Some("swift:3"))
            }

            withActivation(wsk.activation, wsk.action.invoke(actionName, Map("lines" -> lines, "num" -> JsNumber(2)))) {
                _.fields("response").toString should include(""""lines":["seven","eight"]""")
            }
    }

    /**
      * Test the Node.js "sort" action
      */
    it should "sort an array of strings using the node.js sort action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val wsk = new Wsk(usePythonCLI)

            withActivation(wsk.activation, wsk.action.invoke("/whisk.system/util/sort", Map("lines" -> lines))) {
                _.fields("response").toString should include(""""lines":["eight","nine","seven"]""")
            }
    }

    /**
      * Test the "sort" action using Node.js 6
      */
    it should "sort an array of strings using the sort action on nodejs 6" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val wsk = new Wsk(usePythonCLI)

            val file = TestUtils.getCatalogFilename("utils/sort.js")
            val actionName = "sortNodejs6"

            assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
                action.create(name = actionName, artifact = Some(file), kind = Some("nodejs:6"))
            }

            withActivation(wsk.activation, wsk.action.invoke(actionName, Map("lines" -> lines))) {
                _.fields("response").toString should include(""""lines":["eight","nine","seven"]""")
            }
    }

    /**
      * Test the Swift "sort" action using Swift 3
      */
    it should "sort an array of strings using the swift sort action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val wsk = new Wsk(usePythonCLI)

            val file = TestUtils.getCatalogFilename("utils/sort.swift")
            val actionName = "sortSwift3"

            assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
                action.create(name = actionName, artifact = Some(file), kind = Some("swift:3"))
            }

            withActivation(wsk.activation, wsk.action.invoke(actionName, Map("lines" -> lines))) {
                _.fields("response").toString should include(""""lines":["eight","nine","seven"]""")
            }
    }

    /**
      * Test the Node.js "wordCount" action
      */
    it should "count the number of words in a string using the node.js word count action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val wsk = new Wsk(usePythonCLI)

            withActivation(wsk.activation, wsk.action.invoke("/whisk.system/samples/wordCount", Map("payload" -> "one two three".toJson))) {
                _.fields("response").toString should include(""""count":3""")
            }
    }

    /**
      * Test the "wordCount" action using Node.js 6
      */
    it should "count the number of words in a string using the word count action on nodejs 6" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val wsk = new Wsk(usePythonCLI)

            val file = TestUtils.getCatalogFilename("samples/wc.js")
            val actionName = "wcNodejs6"

            assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
                action.create(name = actionName, artifact = Some(file), kind = Some("nodejs:6"))
            }

            withActivation(wsk.activation, wsk.action.invoke(actionName, Map("payload" -> "one two three".toJson))) {
                _.fields("response").toString should include(""""count":3""")
            }
    }

    /**
      * Test the Swift "wordCount" action using Swift 3
      */
    it should "count the number of words in a string using the swift word count action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val wsk = new Wsk(usePythonCLI)

            val file = TestUtils.getCatalogFilename("samples/wc.swift")
            val actionName = "wcSwift3"

            assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
                action.create(name = actionName, artifact = Some(file), kind = Some("swift:3"))
            }

            withActivation(wsk.activation, wsk.action.invoke(actionName, Map("payload" -> "one two three".toJson))) {
                _.fields("response").toString should include(""""count":3""")
            }
    }

}

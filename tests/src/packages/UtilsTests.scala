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
    val wsk = new Wsk()
    val lines = JsArray(JsString("seven"), JsString("eight"), JsString("nine"))

    behavior of "Util Actions"

    /**
      * Test the Node.js "cat" action
      */
    it should "concatenate an array of strings using the node.js cat action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val wsk = new Wsk()

            withActivation(wsk.activation, wsk.action.invoke("/whisk.system/util/cat", Map("lines" -> lines))) {
                _.fields("response").toString should include("\"payload\":\"seven\\neight\\nnine\"")
            }
    }

    /**
      * Test the Swift "cat" action
      */
    it should "concatenate an array of strings using the swift cat action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val wsk = new Wsk()

            val file = TestUtils.getCatalogFilename("utils/cat.swift")
            val actionName = "catSwift"

            assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
                action.create(name = actionName, artifact = Some(file), kind = Some("swift:3"), expectedExitCode = 0)
            }

            withActivation(wsk.activation, wsk.action.invoke(actionName, Map("lines" -> lines))) {
                _.fields("response").toString should include("\"payload\":\"seven\\neight\\nnine\\n\"")
            }

    }

    /**
      * Test the Node.js "split" action
      */
    it should "split a string into an array of strings using the node.js split action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val wsk = new Wsk()

            withActivation(wsk.activation, wsk.action.invoke("/whisk.system/util/split", Map("payload" -> "seven,eight,nine".toJson, "separator" -> ",".toJson))) {
                _.fields("response").toString should include ("\"lines\":[\"seven\",\"eight\",\"nine\"]")
            }
    }

    /**
      * Test the Swift "split" action
      */
    it should "split a string into an array of strings using the swift split action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val wsk = new Wsk()

            val file = TestUtils.getCatalogFilename("utils/split.swift")
            val actionName = "splitSwift"

            assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
                action.create(name = actionName, artifact = Some(file), kind = Some("swift:3"), expectedExitCode = 0)
            }

            withActivation(wsk.activation, wsk.action.invoke(actionName, Map("payload" -> "seven,eight,nine".toJson, "separator" -> ",".toJson))) {
                _.fields("response").toString should include ("\"lines\":[\"seven\",\"eight\",\"nine\"]")
            }
    }

    /**
      * Test the Node.js "head" action
      */
    it should "extract first n elements of an array of strings using the node.js head action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val wsk = new Wsk()

            withActivation(wsk.activation, wsk.action.invoke("/whisk.system/util/head", Map("lines" -> lines, "num" -> JsNumber(2)))) {
                _.fields("response").toString should include("\"lines\":[\"seven\",\"eight\"]")
            }
    }

    /**
      * Test the Swift "head" action
      */
    it should "extract first n elements of an array of strings using the swift head action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val wsk = new Wsk()

            val file = TestUtils.getCatalogFilename("utils/head.swift")
            val actionName = "headSwift"

            assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
                action.create(name = actionName, artifact = Some(file), kind = Some("swift:3"), expectedExitCode = 0)
            }

            withActivation(wsk.activation, wsk.action.invoke(actionName, Map("lines" -> lines, "num" -> JsNumber(2)))) {
                _.fields("response").toString should include("\"lines\":[\"seven\",\"eight\"]")
            }
    }

    /**
      * Test the Node.js "sort" action
      */
    it should "sort an array of strings using the node.js sort action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val wsk = new Wsk()

            withActivation(wsk.activation, wsk.action.invoke("/whisk.system/util/sort", Map("lines" -> lines))) {
                _.fields("response").toString should include("\"lines\":[\"eight\",\"nine\",\"seven\"]")
            }
    }

    /**
      * Test the Swift "sort" action
      */
    it should "sort an array of strings using the swift sort action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val wsk = new Wsk()

            val file = TestUtils.getCatalogFilename("utils/sort.swift")
            val actionName = "sortSwift"

            assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
                action.create(name = actionName, artifact = Some(file), kind = Some("swift:3"), expectedExitCode = 0)
            }

            withActivation(wsk.activation, wsk.action.invoke(actionName, Map("lines" -> lines))) {
                _.fields("response").toString should include("\"lines\":[\"eight\",\"nine\",\"seven\"]")
            }
    }

    /**
      * Test the Node.js "wordCount" action
      */
    it should "count the number of words in a string using the node.js word count action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val wsk = new Wsk()

            withActivation(wsk.activation, wsk.action.invoke("/whisk.system/samples/wordCount", Map("payload" -> "one two three".toJson))) {
                _.fields("response").toString should include("\"count\":3")
            }
    }

    /**
      * Test the Swift "wordCount" action
      */
    it should "count the number of words in a string using the swift word count action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val wsk = new Wsk()

            val file = TestUtils.getCatalogFilename("samples/wc.swift")
            val actionName = "wcSwift"

            assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
                action.create(name = actionName, artifact = Some(file), kind = Some("swift:3"), expectedExitCode = 0)
            }

            withActivation(wsk.activation, wsk.action.invoke(actionName, Map("payload" -> "one two three".toJson))) {
                _.fields("response").toString should include("\"count\":3")
            }
    }

}

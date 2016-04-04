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

package actionContainers

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfter
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import spray.json._

import ActionContainer.withContainer

import scala.util.Random

@RunWith(classOf[JUnitRunner])
class SwiftActionContainerTests extends FlatSpec
    with Matchers
    with BeforeAndAfter {

    // Helpers specific to swiftaction
    def withSwiftContainer(code: ActionContainer => Unit) = withContainer("whisk/swiftaction")(code)
    def initPayload(code: String) = JsObject(
        "value" -> JsObject(
            "name" -> JsString("someSwiftAction"),
            "code" -> JsString(code)))
    def runPayload(args: JsValue) = JsObject("value" -> args)

    behavior of "whisk/swiftaction"

    it should "support valid flows" in {
        val (out, err) = withSwiftContainer { c =>
            val code = """
                | func main(args: [String: Any]) -> [String: Any] {
                |     return args
                | }
            """.stripMargin

            val (initCode, _) = c.init(initPayload(code))

            initCode should be(200)

            val argss = List(
                JsObject("greeting" -> JsString("hi!")),
                JsObject("numbers" -> JsArray(JsNumber(42), JsNumber(1))))

            for (args <- argss) {
                val (runCode, out) = c.run(runPayload(args))
                runCode should be(200)
                out should be(Some(args))
            }
        }

        out.trim shouldBe empty
        err.trim shouldBe empty
    }

    it should "return some error on action error" in {
        withSwiftContainer { c =>
            val code = """
                | // You need an indirection, or swiftc detects the div/0
                | // at compile-time. Smart.
                | func div(x: Int, _ y: Int) -> Int {
                |     return x/y
                | }
                | func main(args: [String: Any]) -> [String: Any] {
                |     return [ "divBy0": div(5,0) ]
                | }
            """.stripMargin

            val (initCode, _) = c.init(initPayload(code))
            initCode should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject()))
            runCode should be(502)

            runRes shouldBe defined
            runRes.get.fields.get("error") shouldBe defined
        }
    }

    it should "log compilation errors" in {
        val (_, err) = withSwiftContainer { c =>
            val code = """
              | 10 PRINT "Hello!"
              | 20 GOTO 10
            """.stripMargin

            val (initCode, _) = c.init(initPayload(code))
            initCode should not be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject("basic" -> JsString("forever"))))
            runCode should be(502)
        }
        err.toLowerCase should include("error")
    }


    it should "support application errors" in {
        withSwiftContainer { c =>
            val code = """
                | func main(args: [String: Any]) -> [String: Any] {
                |     return [ "error": "sorry" ]
                | }
            """.stripMargin

            val (initCode, _) = c.init(initPayload(code))
            initCode should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject()))
            runCode should be(200) // action writer returning an error is OK

            runRes shouldBe defined
            runRes.get.fields.get("error") shouldBe defined
        }
    }

    it should "enforce that the user returns an object" in {
        withSwiftContainer { c =>
            val code = """
                | func main(args: [String: Any]) -> String {
                |     return "rebel, rebel"
                | }
            """.stripMargin

            val (initCode, _) = c.init(initPayload(code))
            initCode should be(200) // This could change if the action wrapper has strong type checks for `main`.

            val (runCode, runRes) = c.run(runPayload(JsObject()))
            runCode should be(502)
            runRes.get.fields.get("error") shouldBe defined
        }
    }
}

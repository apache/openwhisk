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
class PythonActionContainerTests extends FlatSpec
    with Matchers
    with BeforeAndAfter {

    // Helpers specific to pythonaction
    def withPythonContainer(code: ActionContainer => Unit) = withContainer("whisk/pythonaction")(code)
    def initPayload(code: String) = JsObject(
        "value" -> JsObject(
            "name" -> JsString("somePythonAction"),
            "code" -> JsString(code)))
    def runPayload(args: JsValue) = JsObject("value" -> args)

    behavior of "whisk/pythonaction"

    it should "support valid flows" in {
        val (out, err) = withPythonContainer { c =>
            val code = """
                |def main(dict):
                |    if 'user' in dict:
                |        print("hello " + dict['user'] + "!")
                |        return {"user" : dict['user']}
                |    else:
                |        print("hello world!")
                |        return {"user" : "world"}
                |
            """.stripMargin

            val (initCode, _) = c.init(initPayload(code))

            initCode should be(200)

            val argss = List(
                JsObject("user" -> JsString("Lulu")),
                JsObject("user" -> JsString("Momo")))

            for (args <- argss) {
                val (runCode, out) = c.run(runPayload(args))
                println(out)
                runCode should be(200)
                out should be(Some(args))
            }
        }
        err.trim shouldBe empty
    }

    it should "return some error on action error" in {
        withPythonContainer { c =>
            val code = """
                |def div(x, y):
                |    return x/y
                |
                |def main(dict):
                |    return {"divBy0": div(5,0)}
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
        val (_, err) = withPythonContainer { c =>
            val code = """
              | 10 PRINT "Hello!"
              | 20 GOTO 10
            """.stripMargin

            val (initCode, _) = c.init(initPayload(code))
            // init code does not check anything about the code, so it should return 200
            initCode should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject("basic" -> JsString("forever"))))
            runCode should be(502)
        }
        err.toLowerCase should include("error")
    }


    it should "support application errors" in {
        withPythonContainer { c =>
            val code = """
                |def main(args):
                |    return { "error": "sorry" }
            """.stripMargin

            val (initCode, _) = c.init(initPayload(code))
            initCode should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject()))
            println(runRes)
            runCode should be(200) // action writer returning an error is OK

            runRes shouldBe defined
            runRes.get.fields.get("error") shouldBe defined
        }
    }

    it should "enforce that the user returns an object" in {
        withPythonContainer { c =>
            val code = """
                | def main(args):
                |     return "rebel, rebel"
            """.stripMargin

            val (initCode, _) = c.init(initPayload(code))
            initCode should be(200) // This could change if the action wrapper has strong type checks for `main`.

            val (runCode, runRes) = c.run(runPayload(JsObject()))
            runCode should be(502)
            runRes.get.fields.get("error") shouldBe defined
        }
    }
}

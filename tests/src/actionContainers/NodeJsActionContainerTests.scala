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
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import spray.json._

import ActionContainer.withContainer

@RunWith(classOf[JUnitRunner])
class NodeJsActionContainerTests extends FlatSpec with Matchers {

    // Helpers specific to nodejsaction
    def withNodeJsContainer(code: ActionContainer => Unit) = withContainer("whisk/nodejsaction")(code)
    def initPayload(code: String) = JsObject(
        "value" -> JsObject(
            "name" -> JsString("dummyAction"),
            "code" -> JsString(code),
            "main" -> JsString("main")))
    def runPayload(args: JsValue) = JsObject("value" -> args)

    // Filters out the weird markers inserted by the container (see relevant private code in Invoker.scala)
    def filtered(str: String) = str.replaceAll("XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX", "")

    behavior of "whisk/nodejsaction"

    it should "support valid flows" in {
        val (out, err) = withNodeJsContainer { c =>
            val code = """
                | function main(args) {
                |     return args;
                | }
            """.stripMargin

            val (initCode, _) = c.init(initPayload(code))

            initCode should be(200)

            val argss = List(
                JsObject("greeting" -> JsString("hi!")),
                JsObject("numbers" -> JsArray(List(JsNumber(42), JsNumber(1)))))

            for (args <- argss) {
                val (runCode, out) = c.run(runPayload(args))
                runCode should be(200)
                out should be(Some(args))
            }
        }

        filtered(out).trim shouldBe empty
        err.trim shouldBe empty
    }

    it should "fail to initialize with bad code" in {
        val (out, err) = withNodeJsContainer { c =>
            val code = """
                | 10 PRINT "Hello world!"
                | 20 GOTO 10
            """.stripMargin

            val (initCode, _) = c.init(initPayload(code))

            initCode should not be (200)
        }

        // Somewhere, the logs should mention an error occurred.
        val combined = filtered(out) + err
        combined.toLowerCase should include("error")
        combined.toLowerCase should include("syntax")
    }

    it should "return some error on action error" in {
        withNodeJsContainer { c =>
            val code = """
                | function main(args) {
                |     throw "nooooo";
                | }
            """.stripMargin

            val (initCode, _) = c.init(initPayload(code))
            initCode should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject()))
            runCode should not be (200)

            runRes shouldBe defined
            runRes.get.fields.get("error") shouldBe defined
        }
    }

    it should "support application errors" in {
        withNodeJsContainer { c =>
            val code = """
                | function main(args) {
                |     return { "error" : "sorry" };
                | }
            """.stripMargin;

            val (initCode, _) = c.init(initPayload(code))
            initCode should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject()))
            runCode should be(200) // action writer returning an error is OK

            runRes shouldBe defined
            runRes.get.fields.get("error") shouldBe defined
        }
    }

    it should "enforce that the user returns an object" in {
        withNodeJsContainer { c =>
            val code = """
                | function main(args) {
                |     return "rebel, rebel";
                | }
            """.stripMargin

            val (initCode, _) = c.init(initPayload(code))
            initCode should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject()))

            runCode should be(502)
            runRes.get.fields.get("error") shouldBe defined
            // We'd like the error message to mention the broken type.
            runRes.get.fields("error").toString should include("string")
        }
    }

    it should "not warn when using whisk.done" in {
        val (out, err) = withNodeJsContainer { c =>
            val code = """
                | function main(args) {
                |     whisk.done({ "happy": "penguins" });
                | }
            """.stripMargin

            c.init(initPayload(code))._1 should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject()))
            runCode should be(200)
            runRes should be(Some(JsObject("happy" -> JsString("penguins"))))
        }

        filtered(out).trim shouldBe empty
        err.trim shouldBe empty
    }

    it should "not warn when returning whisk.done" in {
        val (out, err) = withNodeJsContainer { c =>
            val code = """
                | function main(args) {
                |     whisk.done({ "happy": "penguins" });
                | }
            """.stripMargin

            c.init(initPayload(code))._1 should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject()))
            runCode should be(200)
            runRes should be(Some(JsObject("happy" -> JsString("penguins"))))
        }

        filtered(out).trim shouldBe empty
        err.trim shouldBe empty
    }

    it should "warn when using whisk.done twice" in {
        val (out, err) = withNodeJsContainer { c =>
            val code = """
                | function main(args) {
                |     setTimeout(function () { whisk.done(); }, 100);
                |     setTimeout(function () { whisk.done(); }, 101);
                |     return whisk.async();
                | }
            """.stripMargin

            c.init(initPayload(code))._1 should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject()))

            runCode should be(200) // debatable, although it seems most logical
            runRes should be(Some(JsObject()))
        }

        val combined = filtered(out) + err
        combined.toLowerCase should include("more than once")
    }

    it should "support the documentation examples (1)" in {
        val (out, err) = withNodeJsContainer { c =>
            val code = """
                | // an action in which each path results in a synchronous activation
                | function main(params) {
                |     if (params.payload == 0) {
                |         return;
                |     } else if (params.payload == 1) {
                |         return whisk.done();    // indicates normal completion
                |     } else if (params.payload == 2) {
                |         return whisk.error();   // indicates abnormal completion
                |     }
                | }
            """.stripMargin

            c.init(initPayload(code))._1 should be(200)

            val (c1, r1) = c.run(runPayload(JsObject("payload" -> JsNumber(0))))
            val (c2, r2) = c.run(runPayload(JsObject("payload" -> JsNumber(1))))
            val (c3, r3) = c.run(runPayload(JsObject("payload" -> JsNumber(2))))

            c1 should be(200)
            r1 should be(Some(JsObject()))

            c2 should be(200)
            r2 should be(Some(JsObject()))

            c3 should be(200) // application error, not container or system
            r3.get.fields.get("error") shouldBe defined
        }

        filtered(out).trim shouldBe empty
        err.trim shouldBe empty
    }

    it should "support the documentation examples (2)" in {
        val (out, err) = withNodeJsContainer { c =>
            val code = """
                | function main() {
                |     setTimeout(function() {
                |         return whisk.done({ done: true });
                |     }, 100);
                |     return whisk.async();
                | }
            """.stripMargin

            c.init(initPayload(code))._1 should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject()))
            runCode should be(200)
            runRes should be(Some(JsObject("done" -> JsBoolean(true))))
        }

        filtered(out).trim shouldBe empty
        err.trim shouldBe empty
    }

    it should "support the documentation examples (3)" in {
        val (out, err) = withNodeJsContainer { c =>
            val code = """
                | function main(params) {
                |     if (params.payload) {
                |         setTimeout(function() {
                |             return whisk.done({done: true});
                |         }, 100);
                |         return whisk.async();  // asynchronous activation
                |     } else {
                |         return whisk.done();   // synchronous activation
                |     }
                | }
            """.stripMargin

            c.init(initPayload(code))._1 should be(200)

            val (c1, r1) = c.run(runPayload(JsObject()))
            val (c2, r2) = c.run(runPayload(JsObject("payload" -> JsBoolean(true))))

            c1 should be(200)
            r1 should be(Some(JsObject()))

            c2 should be(200)
            r2 should be(Some(JsObject("done" -> JsBoolean(true))))
        }

        filtered(out).trim shouldBe empty
        err.trim shouldBe empty
    }
}

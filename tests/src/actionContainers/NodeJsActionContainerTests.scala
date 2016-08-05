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
import org.scalatest.junit.JUnitRunner

import ActionContainer.withContainer
import common.WskActorSystem
import spray.json._

@RunWith(classOf[JUnitRunner])
class NodeJsActionContainerTests extends BasicActionRunnerTests with WskActorSystem {

    lazy val nodejsContainerImageName = "whisk/nodejsaction"

    override def withActionContainer(env: Map[String, String] = Map.empty)(code: ActionContainer => Unit) = {
        withContainer(nodejsContainerImageName, env)(code)
    }

    def withNodeJsContainer(code: ActionContainer => Unit) = withActionContainer()(code)

    override def initPayload(code: String) = JsObject(
        "value" -> JsObject(
            "name" -> JsString("dummyAction"),
            "code" -> JsString(code),
            "main" -> JsString("main")))

    behavior of nodejsContainerImageName

    testNotReturningJson(
        """
        |function main(args) {
        |    return "not a json object"
        |}
        """.stripMargin)

    testEcho(Seq {
        ("node", """
          |function main(args) {
          |    console.log('hello stdout')
          |    console.error('hello stderr')
          |    return args
          |}
          """.stripMargin)
    })

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
        checkStreams(out, err, {
            case (o, e) =>
                (o + e).toLowerCase should include("error")
                (o + e).toLowerCase should include("syntax")
        })
    }

    it should "fail to initialize with no code" in {
        val (out, err) = withNodeJsContainer { c =>
            val code = ""

            val (initCode, error) = c.init(initPayload(code))

            initCode should not be (200)
            error shouldBe a[Some[_]]
            error.get shouldBe a[JsObject]
            error.get.fields("error").toString should include("no code to execute")
        }
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

        checkStreams(out, err, {
            case (o, e) =>
                o shouldBe empty
                e shouldBe empty
        })
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

        checkStreams(out, err, {
            case (o, e) =>
                o shouldBe empty
                e shouldBe empty
        })
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

        checkStreams(out, err, {
            case (o, e) =>
                o should include("more than once")
                e shouldBe empty
        })
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

        checkStreams(out, err, {
            case (o, e) =>
                o shouldBe empty
                e shouldBe empty
        }, 3)
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

        checkStreams(out, err, {
            case (o, e) =>
                o shouldBe empty
                e shouldBe empty
        })
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

        checkStreams(out, err, {
            case (o, e) =>
                o shouldBe empty
                e shouldBe empty
        }, 2)
    }

    it should "error when requiring a non-existent package" in {
        // NPM package names cannot start with a dot, and so there is no danger
        // of the package below ever being valid.
        // https://docs.npmjs.com/files/package.json
        val (out, err) = withNodeJsContainer { c =>
            val code = """
                | function main(args) {
                |     require('.mildlyinvalidnameofanonexistentpackage');
                | }
            """.stripMargin

            val (initCode, _) = c.init(initPayload(code))

            initCode should be(200)

            val (runCode, out) = c.run(runPayload(JsObject()))

            runCode should not be (200)
        }

        // Somewhere, the logs should mention an error occurred.
        checkStreams(out, err, {
            case (o, e) => (o + e) should include("MODULE_NOT_FOUND")
        })
    }

    it should "have ws and socket.io-client packages available" in {
        // GIVEN that it should "error when requiring a non-existent package" (see test above for this)
        val (out, err) = withNodeJsContainer { c =>
            val code = """
                | function main(args) {
                |     require('ws');
                |     require('socket.io-client');
                | }
            """.stripMargin

            val (initCode, _) = c.init(initPayload(code))

            initCode should be(200)

            // WHEN I run an action that requires ws and socket.io.client
            val (runCode, out) = c.run(runPayload(JsObject()))

            // THEN it should pass only when these packages are available
            runCode should be(200)
        }

        checkStreams(out, err, {
            case (o, e) =>
                o shouldBe empty
                e shouldBe empty
        })
    }

    it should "support resolved promises" in {
        val (out, err) = withNodeJsContainer { c =>
            val code = """
            | function main(args) {
            |     return new Promise(function(resolve, reject) {
            |       setTimeout(function() {
            |         resolve({ done: true });
            |       }, 100);
            |    })
            | }
            """.stripMargin

            c.init(initPayload(code))._1 should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject()))
            runCode should be(200)
            runRes should be(Some(JsObject("done" -> JsBoolean(true))))
        }

        checkStreams(out, err, {
            case (o, e) =>
                o shouldBe empty
                e shouldBe empty
        })
    }

    it should "support rejected promises" in {
        val (out, err) = withNodeJsContainer { c =>
            val code = """
            | function main(args) {
            |     return new Promise(function(resolve, reject) {
            |       setTimeout(function() {
            |         reject({ done: true });
            |       }, 100);
            |    })
            | }
            """.stripMargin

            c.init(initPayload(code))._1 should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject()))

            runCode should be(200)
            runRes.get.fields.get("error") shouldBe defined
        }

        checkStreams(out, err, {
            case (o, e) =>
                o shouldBe empty
                e shouldBe empty
        })
    }
}

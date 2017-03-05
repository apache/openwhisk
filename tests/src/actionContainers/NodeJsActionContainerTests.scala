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
import ResourceHelpers.ZipBuilder

import common.WskActorSystem
import spray.json._

@RunWith(classOf[JUnitRunner])
class NodeJsActionContainerTests extends BasicActionRunnerTests with WskActorSystem {

    lazy val nodejsContainerImageName = "nodejsaction"

    override def withActionContainer(env: Map[String, String] = Map.empty)(code: ActionContainer => Unit) = {
        withContainer(nodejsContainerImageName, env)(code)
    }

    def withNodeJsContainer(code: ActionContainer => Unit) = withActionContainer()(code)

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

    testEnv(Seq {
        ("node", """
         |function main(args) {
         |    return {
         |       "api_host": process.env['__OW_API_HOST'],
         |       "api_key": process.env['__OW_API_KEY'],
         |       "namespace": process.env['__OW_NAMESPACE'],
         |       "action_name": process.env['__OW_ACTION_NAME'],
         |       "activation_id": process.env['__OW_ACTIVATION_ID'],
         |       "deadline": process.env['__OW_DEADLINE']
         |    }
         |}
         """.stripMargin.trim)
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
            runRes.get.fields("error").toString.toLowerCase should include("nooooo")
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

    it should "support the documentation examples (1)" in {
        val (out, err) = withNodeJsContainer { c =>
            val code = """
                | // an action in which each path results in a synchronous activation
                | function main(params) {
                |     if (params.payload == 0) {
                |         return;
                |     } else if (params.payload == 1) {
                |         return {payload: 'Hello, World!'}         // indicates normal completion
                |     } else if (params.payload == 2) {
                |         return {error: 'payload must be 0 or 1'}  // indicates abnormal completion
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
            r2 should be(Some(JsObject("payload" -> JsString("Hello, World!"))))

            c3 should be(200) // application error, not container or system
            r3.get.fields.get("error") shouldBe Some(JsString("payload must be 0 or 1"))
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
                | function main(params) {
                |     if (params.payload) {
                |         // asynchronous activation
                |         return new Promise(function(resolve, reject) {
                |                setTimeout(function() {
                |                  resolve({ done: true });
                |                }, 100);
                |             })
                |     } else {
                |         // synchronous activation
                |         return {done: true};
                |     }
                | }
            """.stripMargin

            c.init(initPayload(code))._1 should be(200)

            val (c1, r1) = c.run(runPayload(JsObject()))
            val (c2, r2) = c.run(runPayload(JsObject("payload" -> JsBoolean(true))))

            c1 should be(200)
            r1 should be(Some(JsObject("done" -> JsBoolean(true))))

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

    it should "support rejected promises with no message" in {
        val (out, err) = withNodeJsContainer { c =>
            val code = """
                | function main(args) {
                |     return new Promise(function (resolve, reject) {
                |         reject();
                |     });
                | }""".stripMargin

            c.init(initPayload(code))._1 should be(200)
            val (runCode, runRes) = c.run(runPayload(JsObject()))
            runRes.get.fields.get("error") shouldBe defined
        }
    }

    it should "support large-ish actions" in {
        val thought = " I took the one less traveled by, and that has made all the difference."
        val assignment = "    x = \"" + thought + "\";\n"

        val code = """
            | function main(args) {
            |     var x = "hello";
            """.stripMargin + (assignment * 7000) + """
            |     x = "world";
            |     return { "message" : x };
            | }
            """.stripMargin

        // Lest someone should make it too easy.
        code.length should be >= 500000

        val (out, err) = withNodeJsContainer { c =>
            c.init(initPayload(code))._1 should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject()))

            runCode should be(200)
            runRes.get.fields.get("message") shouldBe Some(JsString("world"))
        }

        checkStreams(out, err, {
            case (o, e) =>
                o shouldBe empty
                e shouldBe empty
        })
    }

    val examplePackageDotJson: String = """
        | {
        |   "name": "wskaction",
        |   "version": "1.0.0",
        |   "description": "An OpenWhisk action as an npm package.",
        |   "main": "index.js",
        |   "author": "info@openwhisk.org",
        |   "license": "Apache-2.0"
        | }
    """.stripMargin

    it should "support zip-encoded npm package actions" in {
        val srcs = Seq(
            Seq("package.json") -> examplePackageDotJson,
            Seq("index.js") -> """
                | exports.main = function (args) {
                |     var name = typeof args["name"] === "string" ? args["name"] : "stranger";
                |
                |     return {
                |         greeting: "Hello " + name + ", from an npm package action."
                |     };
                | }
            """.stripMargin)

        val code = ZipBuilder.mkBase64Zip(srcs)

        val (out, err) = withNodeJsContainer { c =>
            c.init(initPayload(code))._1 should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject()))

            runCode should be(200)
            runRes.get.fields.get("greeting") shouldBe Some(JsString("Hello stranger, from an npm package action."))
        }

        checkStreams(out, err, {
            case (o, e) =>
                o shouldBe empty
                e shouldBe empty
        })
    }

    it should "fail gracefully on invalid zip files" in {
        // Some text-file encoded to base64.
        val code = "Q2VjaSBuJ2VzdCBwYXMgdW4gemlwLgo="

        val (out, err) = withNodeJsContainer { c =>
            c.init(initPayload(code))._1 should not be (200)
        }

        // Somewhere, the logs should mention the connection to the archive.
        checkStreams(out, err, {
            case (o, e) =>
                (o + e).toLowerCase should include("error")
                (o + e).toLowerCase should include("uncompressing")
        })
    }

    it should "fail gracefully on valid zip files that are not actions" in {
        val srcs = Seq(
            Seq("hello") -> """
                | Hello world!
            """.stripMargin)

        val code = ZipBuilder.mkBase64Zip(srcs)

        val (out, err) = withNodeJsContainer { c =>
            c.init(initPayload(code))._1 should not be (200)
        }

        checkStreams(out, err, {
            case (o, e) =>
                (o + e).toLowerCase should include("error")
                (o + e).toLowerCase should include("package.json must be located at the root of a zipped action")
        })
    }

    it should "support actions using non-default entry point" in {
        val (out, err) = withNodeJsContainer { c =>
            val code = """
            | function niam(args) {
            |     return { result: "it works" };
            | }
            """.stripMargin

            c.init(initPayload(code, main = "niam"))._1 should be(200)
            val (runCode, runRes) = c.run(runPayload(JsObject()))
            runRes.get.fields.get("result") shouldBe Some(JsString("it works"))
        }
    }

    it should "support zipped actions using non-default entry point" in {
        val srcs = Seq(
            Seq("package.json") -> examplePackageDotJson,
            Seq("index.js") -> """
                | exports.niam = function (args) {
                |     return { result: "it works" };
                | }
            """.stripMargin)

        val code = ZipBuilder.mkBase64Zip(srcs)

        withNodeJsContainer { c =>
            c.init(initPayload(code, main = "niam"))._1 should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject()))
            runRes.get.fields.get("result") shouldBe Some(JsString("it works"))
        }
    }

    it should "handle unicode in source, input params, logs, and result" in {
        val (out, err) = withNodeJsContainer { c =>
            val code = """
                | function main(args) {
                |   var str = args.delimiter + " ☃ " + args.delimiter;
                |   console.log(str);
                |   return { "winter": str };
                | }
            """.stripMargin

            c.init(initPayload(code))._1 should be(200)
            val (runCode, runRes) = c.run(runPayload(JsObject("delimiter" -> JsString("❄"))))
            runRes.get.fields.get("winter") shouldBe Some(JsString("❄ ☃ ❄"))
        }

        checkStreams(out, err, {
            case (o, _) =>
                o.toLowerCase should include("❄ ☃ ❄")
        })
    }
}

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
import spray.json.JsObject
import spray.json.JsString
import common.WskActorSystem

@RunWith(classOf[JUnitRunner])
class PythonActionContainerTests extends BasicActionRunnerTests with WskActorSystem {

    override def withActionContainer(env: Map[String, String] = Map.empty)(code: ActionContainer => Unit) = {
        withContainer("pythonaction", env)(code)
    }

    behavior of "pythonaction"

    testNotReturningJson(
        """
        |def main(args):
        |    return "not a json object"
        """.stripMargin, checkResultInLogs = false)

    testEcho(Seq {
        ("python", """
          |import sys
          |def main(dict):
          |    print 'hello stdout'
          |    print >> sys.stderr, 'hello stderr'
          |    return dict
          """.stripMargin)
    })

    testEnv(Seq {
        ("python", """
         |import os
         |def main(dict):
         |    return { "auth": os.environ['AUTH_KEY'], "edge": os.environ['EDGE_HOST'] }
         """.stripMargin.trim)
    })

    it should "return on action error when action fails" in {
        val (out, err) = withActionContainer() { c =>
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

        checkStreams(out, err, {
            case (o, e) =>
                o shouldBe empty
                e should include("Traceback")
        })
    }

    it should "log compilation errors" in {
        val (out, err) = withActionContainer() { c =>
            val code = """
              | 10 PRINT "Hello!"
              | 20 GOTO 10
            """.stripMargin

            val (initCode, res) = c.init(initPayload(code))
            // init checks whether compilation was successful, so return 502
            initCode should be(502)

            val (runCode, runRes) = c.run(runPayload(JsObject("basic" -> JsString("forever"))))
            runCode should be(502)
        }

        checkStreams(out, err, {
            case (o, e) =>
                o shouldBe empty
                e should include("Traceback")
        })
    }

    it should "support application errors" in {
        val (out, err) = withActionContainer() { c =>
            val code = """
                |def main(args):
                |    return { "error": "sorry" }
            """.stripMargin

            val (initCode, _) = c.init(initPayload(code))
            initCode should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject()))
            runCode should be(200) // action writer returning an error is OK

            runRes shouldBe defined
            runRes should be(Some(JsObject("error" -> JsString("sorry"))))
        }

        checkStreams(out, err, {
            case (o, e) =>
                o shouldBe empty
                e shouldBe empty
        })
    }
}

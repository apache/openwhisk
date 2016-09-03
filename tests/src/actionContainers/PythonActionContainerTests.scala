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
import spray.json.DefaultJsonProtocol._
import spray.json._
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

    it should "error when importing a not-supported package" in {
        val (out, err) = withActionContainer() { c =>
            val code = """
                |import iamnotsupported
                |def main(args):
                |    return { "error": "not reaching here" }
            """.stripMargin

            val (initCode, res) = c.init(initPayload(code))
            initCode should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject()))
            runCode should be(502)
        }

        checkStreams(out, err, {
            case (o, e) =>
                o shouldBe empty
                e should include("Traceback")
        })
    }

    it should "be able to import additional packages as installed in the image" in {
        val (out, err) = withActionContainer() { c =>
            val code = """
                |from bs4 import BeautifulSoup
                |from dateutil.parser import *
                |import httplib2
                |from lxml import etree
                |import requests
                |from scrapy.item import Item, Field
                |import simplejson as json
                |from twisted.internet import protocol, reactor, endpoints
                |
                |def main(args):
                |    b = BeautifulSoup('<html><head><title>python action test</title></head></html>', 'html.parser')
                |    h = httplib2.Http().request('https://httpbin.org/status/201')[0]
                |    t = parse('2016-02-22 11:59:00 EST')
                |    r = requests.get('https://httpbin.org/status/418')
                |    j = json.dumps({'foo':'bar'}, separators = (',', ':'))
                |
                |    return {
                |       "bs4": str(b.title),
                |       "httplib2": h.status,
                |       "dateutil": t.strftime("%A"),
                |       "lxml": etree.Element("root").tag,
                |       "json": j,
                |       "request": r.status_code
                |    }
            """.stripMargin

            val (initCode, _) = c.init(initPayload(code))
            initCode should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject()))
            runCode should be(200) // action writer returning an error is OK

            runRes shouldBe defined
            runRes should be(Some(JsObject(
                "bs4" -> "<title>python action test</title>".toJson,
                "httplib2" -> 201.toJson,
                "dateutil" -> "Monday".toJson,
                "lxml" -> "root".toJson,
                "json" -> JsObject("foo" -> "bar".toJson).compactPrint.toJson,
                "request" -> 418.toJson)))
        }

        checkStreams(out, err, {
            case (o, e) =>
                o shouldBe empty
                e shouldBe empty
        })
    }
}

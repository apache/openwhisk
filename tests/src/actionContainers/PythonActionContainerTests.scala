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
         |    return {
         |       "api_host": os.environ['__OW_API_HOST'],
         |       "api_key": os.environ['__OW_API_KEY'],
         |       "namespace": os.environ['__OW_NAMESPACE'],
         |       "action_name": os.environ['__OW_ACTION_NAME'],
         |       "activation_id": os.environ['__OW_ACTIVATION_ID'],
         |       "deadline": os.environ['__OW_DEADLINE']
         |    }
         """.stripMargin.trim)
    })

    it should "support actions using non-default entry points" in {
        withActionContainer() { c =>
            val code = """
                |def niam(dict):
                |  return { "result": "it works" }
                |""".stripMargin

            val (initCode, initRes) = c.init(initPayload(code, main = "niam"))
            initCode should be(200)

            val (_, runRes) = c.run(runPayload(JsObject()))
            runRes.get.fields.get("result") shouldBe Some(JsString("it works"))
        }
    }

    it should "handle unicode in source, input params, logs, and result" in {
        val (out, err) = withActionContainer() { c =>
            val code = """
                |def main(dict):
                |    sep = dict['delimiter']
                |    str = sep + " ☃ ".decode('utf-8') + sep
                |    print(str.encode('utf-8'))
                |    return {"winter" : str }
            """.stripMargin

            val (initCode, _) = c.init(initPayload(code))
            initCode should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject("delimiter" -> JsString("❄"))))
            runRes.get.fields.get("winter") shouldBe Some(JsString("❄ ☃ ❄"))
        }

        checkStreams(out, err, {
            case (o, e) =>
                o.toLowerCase should include("❄ ☃ ❄")
                e shouldBe empty
        })
    }

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
                |import socket
                |from kafka import BrokerConnection
                |
                |def main(args):
                |    socket.setdefaulttimeout(120)
                |    b = BeautifulSoup('<html><head><title>python action test</title></head></html>', 'html.parser')
                |    h = httplib2.Http().request('https://openwhisk.ng.bluemix.net/api/v1')[0]
                |    t = parse('2016-02-22 11:59:00 EST')
                |    r = requests.get('https://openwhisk.ng.bluemix.net/api/v1')
                |    j = json.dumps({'foo':'bar'}, separators = (',', ':'))
                |    kafka = BrokerConnection("it works", 9093, None)
                |
                |    return {
                |       "bs4": str(b.title),
                |       "httplib2": h.status,
                |       "dateutil": t.strftime("%A"),
                |       "lxml": etree.Element("root").tag,
                |       "json": j,
                |       "request": r.status_code,
                |       "kafka_python": kafka.host
                |    }
            """.stripMargin

            val (initCode, _) = c.init(initPayload(code))
            initCode should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject()))
            runCode should be(200) // action writer returning an error is OK

            runRes shouldBe defined
            runRes should be(Some(JsObject(
                "bs4" -> "<title>python action test</title>".toJson,
                "httplib2" -> 200.toJson,
                "dateutil" -> "Monday".toJson,
                "lxml" -> "root".toJson,
                "json" -> JsObject("foo" -> "bar".toJson).compactPrint.toJson,
                "request" -> 200.toJson,
                "kafka_python" -> "it works".toJson)))
        }

        checkStreams(out, err, {
            case (o, e) =>
                o shouldBe empty
                e shouldBe empty
        })
    }
}

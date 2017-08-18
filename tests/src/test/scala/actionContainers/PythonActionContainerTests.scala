/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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
import ResourceHelpers.{readAsBase64, ZipBuilder}
import spray.json.DefaultJsonProtocol._
import spray.json._
import common.WskActorSystem
import common.TestUtils
import java.nio.file.Paths

@RunWith(classOf[JUnitRunner])
class PythonActionContainerTests extends BasicActionRunnerTests with WskActorSystem {

  lazy val imageName = "python3action"

  /** indicates if strings in python are unicode by default (i.e., python3 -> true, python2.7 -> false) */
  lazy val pythonStringAsUnicode = true

  override def withActionContainer(env: Map[String, String] = Map.empty)(code: ActionContainer => Unit) = {
    withContainer(imageName, env)(code)
  }

  behavior of imageName

  testNotReturningJson(
    """
        |def main(args):
        |    return "not a json object"
        """.stripMargin,
    checkResultInLogs = false)

  testEcho(Seq {
    (
      "python",
      """
         |from __future__ import print_function
         |import sys
         |def main(args):
         |    print('hello stdout')
         |    print('hello stderr', file=sys.stderr)
         |    return args
         """.stripMargin)
  })

  testUnicode(Seq {
    if (pythonStringAsUnicode) {
      (
        "python",
        """
             |def main(args):
             |    sep = args['delimiter']
             |    str = sep + " ☃ " + sep
             |    print(str)
             |    return {"winter" : str }
             """.stripMargin.trim)
    } else {
      (
        "python",
        """
             |def main(args):
             |    sep = args['delimiter']
             |    str = sep + " ☃ ".decode('utf-8') + sep
             |    print(str.encode('utf-8'))
             |    return {"winter" : str }
             """.stripMargin.trim)
    }
  })

  testEnv(Seq {
    (
      "python",
      """
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

  it should "support zip-encoded action using non-default entry points" in {
    val srcs = Seq(
      Seq("__main__.py") -> """
                |from echo import echo
                |def niam(args):
                |    return echo(args)
            """.stripMargin,
      Seq("echo.py") -> """
                |def echo(args):
                |  return { "echo": args }
            """.stripMargin)

    val code = ZipBuilder.mkBase64Zip(srcs)

    val (out, err) = withActionContainer() { c =>
      val (initCode, initRes) = c.init(initPayload(code, main = "niam"))
      initCode should be(200)

      val args = JsObject("msg" -> JsString("it works"))
      val (runCode, runRes) = c.run(runPayload(args))

      runCode should be(200)
      runRes.get.fields.get("echo") shouldBe Some(args)
    }

    checkStreams(out, err, {
      case (o, e) =>
        o shouldBe empty
        e shouldBe empty
    })
  }

  it should "support zip-encoded action which can read from relative paths" in {
    val srcs = Seq(
      Seq("__main__.py") -> """
                |def main(args):
                |    f = open('workfile', 'r')
                |    return {'file': f.read()}
            """.stripMargin,
      Seq("workfile") -> "this is a test string")

    val code = ZipBuilder.mkBase64Zip(srcs)

    val (out, err) = withActionContainer() { c =>
      val (initCode, initRes) = c.init(initPayload(code))
      initCode should be(200)

      val args = JsObject()
      val (runCode, runRes) = c.run(runPayload(args))

      runCode should be(200)
      runRes.get.fields.get("file") shouldBe Some("this is a test string".toJson)
    }

    checkStreams(out, err, {
      case (o, e) =>
        o shouldBe empty
        e shouldBe empty
    })
  }

  it should "report error if zip-encoded action does not include required file" in {
    val srcs = Seq(Seq("echo.py") -> """
                |def echo(args):
                |  return { "echo": args }
            """.stripMargin)

    val code = ZipBuilder.mkBase64Zip(srcs)

    val (out, err) = withActionContainer() { c =>
      val (initCode, initRes) = c.init(initPayload(code, main = "echo"))
      initCode should be(502)
    }

    checkStreams(out, err, {
      case (o, e) =>
        o shouldBe empty
        e should include("Zip file does not include")
    })
  }

  it should "run zipped Python action containing a virtual environment" in {
    val zippedPythonAction = if (imageName == "python2action") "python2_virtualenv.zip" else "python3_virtualenv.zip"
    val zippedPythonActionName = TestUtils.getTestActionFilename(zippedPythonAction)
    val code = readAsBase64(Paths.get(zippedPythonActionName))

    val (out, err) = withActionContainer() { c =>
      val (initCode, initRes) = c.init(initPayload(code, main = "main"))
      initCode should be(200)
      val args = JsObject("msg" -> JsString("any"))
      val (runCode, runRes) = c.run(runPayload(args))
      runCode should be(200)
      runRes.get.toString() should include("netmask")
    }
    checkStreams(out, err, {
      case (o, e) =>
        o should include("netmask")
        e shouldBe empty
    })
  }

  it should "run zipped Python action containing a virtual environment with non-standard entry point" in {
    val zippedPythonAction = if (imageName == "python2action") "python2_virtualenv.zip" else "python3_virtualenv.zip"
    val zippedPythonActionName = TestUtils.getTestActionFilename(zippedPythonAction)
    val code = readAsBase64(Paths.get(zippedPythonActionName))

    val (out, err) = withActionContainer() { c =>
      val (initCode, initRes) = c.init(initPayload(code, main = "naim"))
      initCode should be(200)
      val args = JsObject("msg" -> JsString("any"))
      val (runCode, runRes) = c.run(runPayload(args))
      runCode should be(200)
      runRes.get.toString() should include("netmask")
    }
    checkStreams(out, err, {
      case (o, e) =>
        o should include("netmask")
        e shouldBe empty
    })
  }

  it should "report error if zipped Python action containing a virtual environment for wrong python version" in {
    val zippedPythonAction = if (imageName == "python3action") "python2_virtualenv.zip" else "python3_virtualenv.zip"
    val zippedPythonActionName = TestUtils.getTestActionFilename(zippedPythonAction)
    val code = readAsBase64(Paths.get(zippedPythonActionName))

    val (out, err) = withActionContainer() { c =>
      val (initCode, initRes) = c.init(initPayload(code, main = "main"))
      initCode should be(200)
      val args = JsObject("msg" -> JsString("any"))
      val (runCode, runRes) = c.run(runPayload(args))
      runCode should be(502)
    }
    checkStreams(out, err, {
      case (o, e) =>
        o shouldBe empty
        if (imageName == "python2action") { e should include("ImportError") }
        if (imageName == "python3action") { e should include("ModuleNotFoundError") }
    })
  }

  it should "report error if zipped Python action has wrong main module name" in {
    val zippedPythonActionWrongName = TestUtils.getTestActionFilename("python_virtualenv_name.zip")

    val code = readAsBase64(Paths.get(zippedPythonActionWrongName))

    val (out, err) = withActionContainer() { c =>
      val (initCode, initRes) = c.init(initPayload(code, main = "main"))
      initCode should be(502)
    }
    checkStreams(out, err, {
      case (o, e) =>
        o shouldBe empty
        e should include("Zip file does not include __main__.py")
    })
  }

  it should "report error if zipped Python action has invalid virtualenv directory" in {
    val zippedPythonActionWrongDir = TestUtils.getTestActionFilename("python_virtualenv_dir.zip")

    val code = readAsBase64(Paths.get(zippedPythonActionWrongDir))
    val (out, err) = withActionContainer() { c =>
      val (initCode, initRes) = c.init(initPayload(code, main = "main"))
      initCode should be(502)
    }
    checkStreams(out, err, {
      case (o, e) =>
        o shouldBe empty
        e should include("Zip file does not include /virtualenv/bin/")
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
      runRes should be(
        Some(JsObject(
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

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

import common.WskActorSystem
import spray.json._

@RunWith(classOf[JUnitRunner])
class BashActionContainerTests extends BasicActionRunnerTests with WskActorSystem {
  // note: "out" will not be empty as the PHP web server outputs a message when
  // it starts up
  val enforceEmptyOutputStream = false

  lazy val bashContainerImageName = "action-bash"

  override def withActionContainer(env: Map[String, String] = Map.empty)(code: ActionContainer => Unit) = {
    withContainer(bashContainerImageName, env)(code)
  }

  def withBashContainer(code: ActionContainer => Unit) = withActionContainer()(code)

  behavior of bashContainerImageName

  testEcho(Seq {
    (
      "bash",
      """
        |#!/bin/bash
        |echo 'hello stdout'
        |echo 'hello stderr' 1>&2
        |echo $1
      """.stripMargin.trim)
  })

  testEnv(Seq {
    (
      "bash",
      """
        |#!/bin/bash
        |echo "{ \
        |\"api_host\": \"$__OW_API_HOST\", \"api_key\": \"$__OW_API_KEY\", \
        |\"namespace\": \"$__OW_NAMESPACE\", \"action_name\": \"$__OW_ACTION_NAME\", \
        |\"activation_id\": \"$__OW_ACTIVATION_ID\", \"deadline\": \"$__OW_DEADLINE\" }"
      """.stripMargin.trim)
  })

  it should "access to string parameter using environment variable" in {
    val (out, err) = withBashContainer { c =>
      val args = JsObject("string" -> JsString("hello"))
      val (initCode, _) = c.init(initPayload("""
                                               |#!/bin/bash
                                               |
                                               |if [ $_string = "hello" ]
                                               |then
                                               |  echo match
                                               |fi
                                             """.stripMargin.trim))
      initCode should be(200)
      val (runCode, out) = c.run(runPayload(args))
      runCode should be(200)
      out should be(Some(JsObject("result" -> JsString("match"))))
    }
  }

  it should "access to number parameter using environment variable" in {
    val (out, err) = withBashContainer { c =>
      val args = JsObject("number" -> JsNumber(93))
      val (initCode, _) = c.init(initPayload("""
                                               |#!/bin/bash
                                               |
                                               |if [ $_number = 93 ]
                                               |then
                                               |  echo match
                                               |fi
                                             """.stripMargin.trim))
      initCode should be(200)
      val (runCode, out) = c.run(runPayload(args))
      runCode should be(200)
      out should be(Some(JsObject("result" -> JsString("match"))))
    }
  }

  it should "access to object using environment variable" in {
    val (out, err) = withBashContainer { c =>
      val args = JsObject("object" -> JsObject("a" -> JsString("A")))
      val (initCode, _) = c.init(initPayload("""
                                               |#!/bin/bash
                                               |
                                               |if [ $_object_a = "A" ]
                                               |then
                                               |  echo match
                                               |fi
                                             """.stripMargin.trim))
      initCode should be(200)
      val (runCode, out) = c.run(runPayload(args))
      runCode should be(200)
      out should be(Some(JsObject("result" -> JsString("match"))))
    }
  }

  it should "access to elements of list using environment variable" in {
    val (out, err) = withBashContainer { c =>
      val args = JsObject("list" -> JsArray(JsString("match1"), JsString("match2"), JsString("match3")))
      val (initCode, _) =
        c.init(initPayload("""
                                               |#!/bin/bash
                                               |
                                               |if [ $_list_0 = "match1" ] && [ $_list_1 = "match2" ] && [ $_list_2 = "match3" ]
                                               |then
                                               |  echo match
                                               |fi
                                             """.stripMargin.trim))
      initCode should be(200)
      val (runCode, out) = c.run(runPayload(args))
      runCode should be(200)
      out should be(Some(JsObject("result" -> JsString("match"))))
    }
  }

  it should "support auto boxing" in {
    val (out, err) = withBashContainer { c =>
      val (initCode, _) = c.init(initPayload("""
                                               |#!/bin/sh
                                               |echo not a json object
                                             """.stripMargin.trim))
      initCode should be(200)
      val (runCode, out) = c.run(JsObject())
      runCode should be(200)
      out should be(Some(JsObject("result" -> JsString("not a json object"))))
    }
  }

  it should "support jq" in {
    val (out, err) = withBashContainer { c =>
      val args =
        JsObject("a" -> JsString("A"), "b" -> JsNumber(123), "c" -> JsArray(JsNumber(1), JsNumber(2), JsNumber(3)))

      val (initCode, _) = c.init(initPayload("""
                                               |#!/bin/bash
                                               |
                                               |ARGS=$@
                                               |A=`echo "$ARGS" | jq '."a"'`
                                               |B=`echo "$ARGS" | jq '."b"'`
                                               |C=`echo "$ARGS" | jq '."c"[0]'`
                                               |RES=$(($B + $C))
                                               |
                                               |echo $RES
                                               |
                                             """.stripMargin.trim))
      initCode should be(200)
      val (runCode, out) = c.run(runPayload(args))
      runCode should be(200)
      out should be(Some(JsObject("result" -> JsString("124"))))
    }
  }

  it should "support unicode characters" in {
    val (out, err) = withBashContainer { c =>
      val args = JsObject("winter" -> JsString("❄ ☃ ❄"))

      val (initCode, _) = c.init(initPayload("""
                                               |#!/bin/sh
                                               |echo $_winter
                                             """.stripMargin.trim))
      initCode should be(200)
      val (runCode, out) = c.run(runPayload(args))
      runCode should be(200)
      out should be(Some(JsObject("result" -> JsString("❄ ☃ ❄"))))
    }
  }

  it should "run and return a only last line" in {
    val (out, err) = withBashContainer { c =>
      val (initCode, _) = c.init(initPayload("""
                                               |#!/bin/sh
                                               |echo This is not a last line
                                               |echo This is a last line
                                             """.stripMargin.trim))
      initCode should be(200)
      val (runCode, out) = c.run(JsObject())
      runCode should be(200)
      out should be(Some(JsObject("result" -> JsString("This is a last line"))))
    }
  }

  it should "run and return a json object" in {
    val (out, err) = withBashContainer { c =>
      val (initCode, _) = c.init(initPayload("""
                                               |#!/bin/sh
                                               |echo "{ \"result\": \"This is a json object\" }"
                                             """.stripMargin.trim))
      initCode should be(200)
      val (runCode, out) = c.run(JsObject())
      runCode should be(200)
      out should be(Some(JsObject("result" -> JsString("This is a json object"))))
    }
  }

  it should "fail to run a bad script" in {
    val (out, err) = withBashContainer { c =>
      val (initCode, _) = c.init(initPayload(""))
      initCode should be(200)
      val (runCode, out) = c.run(JsNull)
      runCode should be(502)
      out should be(Some(JsObject("error" -> JsString("The action did not return a dictionary."))))
    }

    checkStreams(out, err, {
      case (o, _) => o should include("error")
    })
  }
}

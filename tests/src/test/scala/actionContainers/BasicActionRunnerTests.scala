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

import spray.json.DefaultJsonProtocol._
import spray.json._

@RunWith(classOf[JUnitRunner])
trait BasicActionRunnerTests extends ActionProxyContainerTestUtils {
  def withActionContainer(env: Map[String, String] = Map.empty)(code: ActionContainer => Unit): (String, String)

  /**
   * Runs tests for actions which do not return a dictionary and confirms expected error messages.
   * @param codeNotReturningJson code to execute, should not return a JSON object
   * @param checkResultInLogs should be true iff the result of the action is expected to appear in stdout or stderr
   */
  def testNotReturningJson(codeNotReturningJson: String, checkResultInLogs: Boolean = true) = {
    it should "run and report an error for script not returning a json object" in {
      val (out, err) = withActionContainer() { c =>
        val (initCode, _) = c.init(initPayload(codeNotReturningJson))
        initCode should be(200)
        val (runCode, out) = c.run(JsObject())
        runCode should be(502)
        out should be(Some(JsObject("error" -> JsString("The action did not return a dictionary."))))
      }

      checkStreams(out, err, {
        case (o, e) =>
          if (checkResultInLogs) {
            (o + e) should include("not a json object")
          } else {
            o shouldBe empty
            e shouldBe empty
          }
      })
    }
  }

  /**
   * Runs tests for code samples which are expected to echo the input arguments
   * and print hello [stdout, stderr].
   */
  def testEcho(stdCodeSamples: Seq[(String, String)]) = {
    stdCodeSamples.foreach { s =>
      it should s"run a ${s._1} script" in {
        val argss = List(
          JsObject("string" -> JsString("hello")),
          JsObject("string" -> JsString("❄ ☃ ❄")),
          JsObject("numbers" -> JsArray(JsNumber(42), JsNumber(1))),
          // JsObject("boolean" -> JsBoolean(true)), // fails with swift3 returning boolean: 1
          JsObject("object" -> JsObject("a" -> JsString("A"))))

        val (out, err) = withActionContainer() { c =>
          val (initCode, _) = c.init(initPayload(s._2))
          initCode should be(200)

          for (args <- argss) {
            val (runCode, out) = c.run(runPayload(args))
            runCode should be(200)
            out should be(Some(args))
          }
        }

        checkStreams(out, err, {
          case (o, e) =>
            o should include("hello stdout")
            e should include("hello stderr")
        }, argss.length)
      }
    }
  }

  def testUnicode(stdUnicodeSamples: Seq[(String, String)]) = {
    stdUnicodeSamples.foreach { s =>
      it should s"run a ${s._1} action and handle unicode in source, input params, logs, and result" in {
        val (out, err) = withActionContainer() { c =>
          val (initCode, _) = c.init(initPayload(s._2))
          initCode should be(200)

          val (runCode, runRes) = c.run(runPayload(JsObject("delimiter" -> JsString("❄"))))
          runRes.get.fields.get("winter") shouldBe Some(JsString("❄ ☃ ❄"))
        }

        checkStreams(out, err, {
          case (o, _) =>
            o.toLowerCase should include("❄ ☃ ❄")
        })
      }
    }
  }

  /** Runs tests for code samples which are expected to return the expected standard environment {auth, edge}. */
  def testEnv(stdEnvSamples: Seq[(String, String)],
              enforceEmptyOutputStream: Boolean = true,
              enforceEmptyErrorStream: Boolean = true) = {
    stdEnvSamples.foreach { s =>
      it should s"run a ${s._1} script and confirm expected environment variables" in {
        val props = Seq(
          "api_host" -> "xyz",
          "api_key" -> "abc",
          "namespace" -> "zzz",
          "action_name" -> "xxx",
          "activation_id" -> "iii",
          "deadline" -> "123")
        val env = props.map { case (k, v) => s"__OW_${k.toUpperCase()}" -> v }

        val (out, err) = withActionContainer(env.take(1).toMap) { c =>
          val (initCode, _) = c.init(initPayload(s._2))
          initCode should be(200)

          val (runCode, out) = c.run(runPayload(JsObject(), Some(props.toMap.toJson.asJsObject)))
          runCode should be(200)
          out shouldBe defined
          props.map {
            case (k, v) =>
              withClue(k) {
                out.get.fields(k) shouldBe JsString(v)
              }

          }
        }

        checkStreams(out, err, {
          case (o, e) =>
            if (enforceEmptyOutputStream) o shouldBe empty
            if (enforceEmptyErrorStream) e shouldBe empty
        })
      }
    }
  }

  /**
   * Large param samples, echo the input args with input larger than 128K and using STDIN
   */
  def testLargeInput(stdLargeInputSamples: Seq[(String, String)]) = {
    stdLargeInputSamples.foreach { s =>
      it should s"run a ${s._1} script with large input" in {
        val (out, err) = withActionContainer() { c =>
          val (initCode, _) = c.init(initPayload(s._2))
          initCode should be(200)

          val arg = JsObject("arg" -> JsString(("a" * 1048561)))
          val (_, runRes) = c.run(runPayload(arg))
          runRes.get shouldBe arg
        }

      }
    }
  }

}

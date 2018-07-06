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
   * Runs tests for actions which receive an empty initializer (no source or exec).
   */
  def testNoSourceOrExec() = {
    it should "report an error when no code is present for initialization" in {
      val (out, err) = withActionContainer() { c =>
        val (initCode, out) = c.init(initPayload(""))
        initCode should not be (200)
        out should be(Some(JsObject("error" -> JsString("The action did not initialize."))))
      }
    }
  }

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
        val (runCode, out) = c.run(JsObject.empty)
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
   * Tests the echo action for different input parameters.
   * The test actions must also print hello [stdout, stderr] to the respective streams.
   * @param stdCodeSamples a sequence of tuples, where each tuple provide a test name in the first component
   *                       and the identity/echo function in the second component.
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

  /**
   * Tests a unicode action. The action must properly handle unicode characters in the executable,
   * receive a unicode character, and construct a response with a unicode character. It must also
   * emit unicode characters correctly to stdout.
   * @param stdUnicodeSamples a sequence of tuples, where each tuple provide a test name in the first component
   *                          a function in the second component: { delimiter } => { winter: "❄ " + delimiter + " ❄"}
   */
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

  /**
   * Tests the action constructs the activation context correctly.
   *
   * @param stdEnvSamples a sequence of tuples, where each tuple provide a test name in the first component
   *                      and a function returning the activation context consisting of the following dictionary
   *                      {
   *                        "api_host": process.env.__OW_API_HOST,
   *                        "api_key": process.env__OW_API_KEY,
   *                        "namespace": process.env.__OW_NAMESPACE,
   *                        "action_name": process.env.__OW_ACTION_NAME,
   *                        "activation_id": process.env.__OW_ACTIVATION_ID,
   *                        "deadline": process.env.__OW_DEADLINE
   *                      }
   *
   */
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

          val (runCode, out) = c.run(runPayload(JsObject.empty, Some(props.toMap.toJson.asJsObject)))
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
   * Tests the action to confirm it can handle a large parameter (larger than 128K) when using STDIN.
   * @param stdLargeInputSamples a sequence of tuples, where each tuple provide a test name in the first component
   *                             and the identity/echo function in the second component.
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

  /**
   * Tests that an action will not allow more than one initialization and confirms expected error messages.
   * @param code the code to execute, the identity/echo function is sufficient.
   */
  def testInitCannotBeCalledMoreThanOnce(code: String) = {
    it should "fail to initialize a second time" in {
      val errorMessage = "Cannot initialize the action more than once."
      val (out, err) = withActionContainer() { c =>
        val (initCode1, _) = c.init(initPayload(code))
        initCode1 should be(200)

        val (initCode2, error2) = c.init(initPayload(code))
        initCode2 should be(403)
        error2 should be(Some(JsObject("error" -> JsString(errorMessage))))
      }

      checkStreams(out, err, {
        case (o, e) =>
          (o + e) should include(errorMessage)
      })
    }
  }
}

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
import org.scalatest.exceptions.TestPendingException
import org.scalatest.junit.JUnitRunner
import spray.json.DefaultJsonProtocol._
import spray.json._

@RunWith(classOf[JUnitRunner])
trait BasicActionRunnerTests extends ActionProxyContainerTestUtils {

  /**
   * Must be defined by the runtime test suites. A typical implementation looks like this:
   *      withContainer("container-image-name", env)(code)
   * See [[ActionContainer.withContainer]] for details.
   *
   * @param env the environment to pass to the container
   * @param code the code to initialize with
   */
  def withActionContainer(env: Map[String, String] = Map.empty)(code: ActionContainer => Unit): (String, String)

  /**
   * Runs tests for actions which receive an empty initializer (no source or exec).
   *
   * @param stub true if the proxy provides a stub
   */
  def testNoSourceOrExec: TestConfig

  /**
   * Runs tests for actions which receive an empty code initializer (exec with code equal to the empty string).
   *
   * @param stub true if the proxy provides a stub
   */
  def testNoSource = testNoSourceOrExec

  /**
   * Runs tests for actions which do not return a dictionary and confirms expected error messages.
   *
   * @param code code to execute, should not return a JSON object
   * @param main the main function
   * @param checkResultInLogs should be true iff the result of the action is expected to appear in stdout or stderr
   */
  def testNotReturningJson: TestConfig

  /**
   * Tests that an action will not allow more than one initialization and confirms expected error messages.
   *
   * @param code the code to execute, the identity/echo function is sufficient.
   * @param main the main function
   */
  def testInitCannotBeCalledMoreThanOnce: TestConfig

  /**
   * Tests the echo action for an entry point other than "main".
   *
   * @param code the code to execute, must be the identity/echo function.
   * @param main the main function to run, must not be "main"
   */
  def testEntryPointOtherThanMain: TestConfig

  /**
   * Tests the echo action for different input parameters.
   * The test actions must also print hello [stdout, stderr] to the respective streams.
   *
   * @param code the code to execute, must be the identity/echo function.
   * @param main the main function
   */
  def testEcho: TestConfig

  /**
   * Tests a unicode action. The action must properly handle unicode characters in the executable,
   * receive a unicode character, and construct a response with a unicode character. It must also
   * emit unicode characters correctly to stdout.
   *
   * @param code a function { delimiter } => { winter: "❄ " + delimiter + " ❄"}
   * @param main the main function
   */
  def testUnicode: TestConfig

  /**
   * Tests the action constructs the activation context correctly.
   *
   * @param code a function returning the activation context consisting of the following dictionary
   *             { "api_host": process.env.__OW_API_HOST,
   *               "api_key": process.env__OW_API_KEY,
   *               "namespace": process.env.__OW_NAMESPACE,
   *               "action_name": process.env.__OW_ACTION_NAME,
   *               "action_version": process.env.__OW_ACTION_VERSION,
   *               "activation_id": process.env.__OW_ACTIVATION_ID,
   *               "deadline": process.env.__OW_DEADLINE
   *             }
   * @param main the main function
   * @param enforceEmptyOutputStream true to check empty stdout stream
   * @param enforceEmptyErrorStream true to check empty stderr stream
   */
  def testEnv: TestConfig

  /**
   * Tests that action parameters at initialization time are available before an action
   * is initialized. The value of a parameter is always a String (and may include the empty string).
   *
   * @param code a function returning a dictionary consisting of the following properties
   *             { "SOME_VAR" : process.env.SOME_VAR,
   *               "ANOTHER_VAR": process.env.ANOTHER_VAR
   *             }
   * @param main the main function
   */
  def testEnvParameters: TestConfig = TestConfig("", skipTest = true) // so as not to break downstream dependencies

  /**
   * Tests the action to confirm it can handle a large parameter (larger than 128K) when using STDIN.
   *
   * @param code the identity/echo function.
   * @param main the main function
   */
  def testLargeInput: TestConfig

  behavior of "runtime proxy"

  it should "handle initialization with no code" in {
    val config = testNoSource

    val (out, err) = withActionContainer() { c =>
      val (initCode, out) = c.init(initPayload("", ""))
      if (config.hasCodeStub) {
        initCode should be(200)
      } else {
        initCode should not be (200)
      }
    }
  }

  it should "handle initialization with no content" in {
    val config = testNoSourceOrExec

    val (out, err) = withActionContainer() { c =>
      val (initCode, out) = c.init(JsObject.empty)
      if (!config.hasCodeStub) {
        initCode should not be (200)
        out should {
          be(Some(JsObject("error" -> JsString("Missing main/no code to execute.")))) or
            be(Some(
              JsObject("error" -> JsString("The action failed to generate or locate a binary. See logs for details."))))
        }
      } else {
        initCode should be(200)
      }
    }
  }

  it should "run and report an error for function not returning a json object" in {
    val config = testNotReturningJson
    if (!config.skipTest) { // this may not be possible in types languages
      val (out, err) = withActionContainer() { c =>
        val (initCode, _) = c.init(initPayload(config.code, config.main))
        initCode should be(200)
        val (runCode, out) = c.run(JsObject.empty)
        runCode should not be (200)
        out should be(Some(JsObject("error" -> JsString("The action did not return a dictionary."))))
      }

      checkStreams(out, err, {
        case (o, e) =>
          // some runtimes may emita an error message,
          // or for native runtimes emit the result to stdout
          if (config.enforceEmptyOutputStream) o shouldBe empty
          if (config.enforceEmptyErrorStream) e shouldBe empty
      })
    }
  }

  it should "fail to initialize a second time" in {
    val config = testInitCannotBeCalledMoreThanOnce

    val errorMessage = "Cannot initialize the action more than once."

    val (out, err) = withActionContainer() { c =>
      val (initCode1, _) = c.init(initPayload(config.code, config.main))
      initCode1 should be(200)

      val (initCode2, error2) = c.init(initPayload(config.code, config.main))
      initCode2 should not be (200)
      error2 should be(Some(JsObject("error" -> JsString(errorMessage))))
    }

    checkStreams(out, err, {
      case (o, e) =>
        (o + e) should include(errorMessage)
    }, sentinelCount = 0)
  }

  it should s"invoke non-standard entry point" in {
    val config = testEntryPointOtherThanMain
    config.main should not be ("main")

    val (out, err) = withActionContainer() { c =>
      val (initCode, _) = c.init(initPayload(config.code, config.main))
      initCode should be(200)

      val arg = JsObject("string" -> JsString("hello"))
      val (runCode, out) = c.run(runPayload(arg))
      runCode should be(200)
      out should be(Some(arg))
    }

    checkStreams(out, err, {
      case (o, e) =>
        // some native runtimes will emit the result to stdout
        if (config.enforceEmptyOutputStream) o shouldBe empty
        e shouldBe empty
    })
  }

  it should s"echo arguments and print message to stdout/stderr" in {
    val config = testEcho

    val argss = List(
      JsObject("string" -> JsString("hello")),
      JsObject("string" -> JsString("❄ ☃ ❄")),
      JsObject("numbers" -> JsArray(JsNumber(42), JsNumber(1))),
      // JsObject("boolean" -> JsTrue), // fails with swift3 returning boolean: 1
      JsObject("object" -> JsObject("a" -> JsString("A"))))

    val (out, err) = withActionContainer() { c =>
      val (initCode, _) = c.init(initPayload(config.code, config.main))
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
        // some languages may not support printing to stderr
        if (!config.skipTest) e should include("hello stderr")
    }, argss.length)
  }

  it should s"handle unicode in source, input params, logs, and result" in {
    val config = testUnicode

    val (out, err) = withActionContainer() { c =>
      val (initCode, _) = c.init(initPayload(config.code, config.main))
      initCode should be(200)

      val (runCode, runRes) = c.run(runPayload(JsObject("delimiter" -> JsString("❄"))))
      runRes.get.fields.get("winter") shouldBe Some(JsString("❄ ☃ ❄"))
    }

    checkStreams(out, err, {
      case (o, _) =>
        o.toLowerCase should include("❄ ☃ ❄")
    })
  }

  it should s"export environment variables before initialization" in {
    val config = testEnvParameters

    if (config.skipTest) {
      throw new TestPendingException
    } else {
      val env = Map("SOME_VAR" -> JsString("xyz"), "ANOTHER_VAR" -> JsString.empty)

      val (out, err) = withActionContainer() { c =>
        val (initCode, _) = c.init(initPayload(config.code, config.main, Some(env)))
        initCode should be(200)

        val (runCode, out) = c.run(runPayload(JsObject.empty))
        runCode should be(200)
        out shouldBe defined
        val fields = out.get.fields
        fields("SOME_VAR") shouldBe JsString("xyz")
        fields("ANOTHER_VAR") shouldBe JsString("")
      }

      checkStreams(out, err, {
        case (o, e) =>
          if (config.enforceEmptyOutputStream) o shouldBe empty
          if (config.enforceEmptyErrorStream) e shouldBe empty
      })
    }
  }

  it should s"confirm expected environment variables" in {
    val config = testEnv

    val props = Seq(
      "api_host" -> "xyz",
      "api_key" -> "abc",
      "namespace" -> "zzz",
      "action_name" -> "xxx",
      "action_version" -> "0.0.1",
      "activation_id" -> "iii",
      "deadline" -> "123")

    val env = props.map { case (k, v) => s"__OW_${k.toUpperCase()}" -> v }

    // the api host is sent as a docker run environment parameter
    val (out, err) = withActionContainer(env.take(1).toMap) { c =>
      val (initCode, _) = c.init(initPayload(config.code, config.main))
      initCode should be(200)

      // we omit the api host from the run payload so the docker run env var is used
      val (runCode, out) = c.run(runPayload(JsObject.empty, Some(props.drop(1).toMap.toJson.asJsObject)))
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
        if (config.enforceEmptyOutputStream) o shouldBe empty
        if (config.enforceEmptyErrorStream) e shouldBe empty
    })
  }

  it should s"echo a large input" in {
    val config = testLargeInput
    if (config.skipTest) {
      throw new TestPendingException
    } else {
      var passed = true

      val (out, err) = withActionContainer() { c =>
        val (initCode, _) = c.init(initPayload(config.code, config.main))
        initCode should be(200)

        val arg = JsObject("arg" -> JsString(("a" * 1048561)))
        val (_, runRes) = c.run(runPayload(arg))
        if (runRes.get != arg) {
          println(s"result did not match: ${runRes.get}")
          passed = false
        }
      }

      if (!passed) {
        println(out)
        println(err)
        assert(false)
      }
    }
  }

  case class TestConfig(code: String,
                        main: String = "main",
                        enforceEmptyOutputStream: Boolean = true,
                        enforceEmptyErrorStream: Boolean = true,
                        hasCodeStub: Boolean = false,
                        skipTest: Boolean = false)
}

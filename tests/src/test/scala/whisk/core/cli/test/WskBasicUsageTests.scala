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

package whisk.core.cli.test

import java.time.Instant
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock

import scala.language.postfixOps
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.util.Random

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import common.TestHelpers
import common.TestUtils
import common.TestUtils._
import common.WhiskProperties
import common.Wsk
import common.WskProps
import common.WskTestHelpers
import spray.json.DefaultJsonProtocol._
import spray.json._
import whisk.core.entity._
import whisk.core.entity.LogLimit._
import whisk.core.entity.MemoryLimit._
import whisk.core.entity.TimeLimit._
import whisk.core.entity.size.SizeInt
import whisk.utils.retry
import JsonArgsForTests._
import whisk.http.Messages

/**
 * Tests for basic CLI usage. Some of these tests require a deployed backend.
 */
@RunWith(classOf[JUnitRunner])
class WskBasicUsageTests extends TestHelpers with WskTestHelpers {

  implicit val wskprops = WskProps()
  val wsk = new Wsk
  val defaultAction = Some(TestUtils.getTestActionFilename("hello.js"))
  val usrAgentHeaderRegEx = """\bUser-Agent\b": \[\s+"OpenWhisk\-CLI/1.\d+.*"""

  behavior of "Wsk CLI usage"

  it should "show help and usage info" in {
    val stdout = wsk.cli(Seq()).stdout
    stdout should include regex ("""(?i)Usage:""")
    stdout should include regex ("""(?i)Flags""")
    stdout should include regex ("""(?i)Available commands""")
    stdout should include regex ("""(?i)--help""")
  }

  it should "show help and usage info using the default language" in {
    val env = Map("LANG" -> "de_DE")
    // Call will fail with exit code 2 if language not supported
    wsk.cli(Seq("-h"), env = env)
  }

  it should "show cli build version" in {
    val stdout = wsk.cli(Seq("property", "get", "--cliversion")).stdout
    stdout should include regex ("""(?i)whisk CLI version\s+201.*""")
  }

  it should "show api version" in {
    val stdout = wsk.cli(Seq("property", "get", "--apiversion")).stdout
    stdout should include regex ("""(?i)whisk API version\s+v1""")
  }

  it should "reject bad command" in {
    val result = wsk.cli(Seq("bogus"), expectedExitCode = ERROR_EXIT)
    result.stderr should include regex ("""(?i)Run 'wsk --help' for usage""")
  }

  it should "allow a 3 part Fully Qualified Name (FQN) without a leading '/'" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val guestNamespace = wsk.namespace.whois()
      val packageName = "packageName3ptFQN"
      val actionName = "actionName3ptFQN"
      val triggerName = "triggerName3ptFQN"
      val ruleName = "ruleName3ptFQN"
      val fullQualifiedName = s"${guestNamespace}/${packageName}/${actionName}"
      // Used for action and rule creation below
      assetHelper.withCleaner(wsk.pkg, packageName) { (pkg, _) =>
        pkg.create(packageName)
      }
      assetHelper.withCleaner(wsk.trigger, triggerName) { (trigger, _) =>
        trigger.create(triggerName)
      }
      // Test action and rule creation where action name is 3 part FQN w/out leading slash
      assetHelper.withCleaner(wsk.action, fullQualifiedName) { (action, _) =>
        action.create(fullQualifiedName, defaultAction)
      }
      assetHelper.withCleaner(wsk.rule, ruleName) { (rule, _) =>
        rule.create(ruleName, trigger = triggerName, action = fullQualifiedName)
      }

      wsk.action.invoke(fullQualifiedName).stdout should include(s"ok: invoked /$fullQualifiedName")
      wsk.action.get(fullQualifiedName).stdout should include(s"ok: got action ${packageName}/${actionName}")
  }

  it should "include CLI user agent headers with outbound requests" in {
    val stdout = wsk.cli(Seq("list", "--auth", wskprops.authKey) ++ wskprops.overrides, verbose = true).stdout
    stdout should include regex (usrAgentHeaderRegEx)
  }

  behavior of "Wsk actions"

  it should "reject creating entities with invalid names" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val names = Seq(
      ("", ERROR_EXIT),
      (" ", BAD_REQUEST),
      ("hi+there", BAD_REQUEST),
      ("$hola", BAD_REQUEST),
      ("dora?", BAD_REQUEST),
      ("|dora|dora?", BAD_REQUEST))

    names foreach {
      case (name, ec) =>
        assetHelper.withCleaner(wsk.action, name, confirmDelete = false) { (action, _) =>
          action.create(name, defaultAction, expectedExitCode = ec)
        }
    }
  }

  it should "reject create with missing file" in {
    wsk.action.create("missingFile", Some("notfound"), expectedExitCode = MISUSE_EXIT).stderr should include(
      "not a valid file")
  }

  it should "reject action update when specified file is missing" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    // Create dummy action to update
    val name = "updateMissingFile"
    val file = Some(TestUtils.getTestActionFilename("hello.js"))
    assetHelper.withCleaner(wsk.action, name) { (action, name) =>
      action.create(name, file)
    }
    // Update it with a missing file
    wsk.action.create(name, Some("notfound"), update = true, expectedExitCode = MISUSE_EXIT)
  }

  it should "reject action update for sequence with no components" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "updateMissingComponents"
    val file = Some(TestUtils.getTestActionFilename("hello.js"))
    assetHelper.withCleaner(wsk.action, name) { (action, name) =>
      action.create(name, file)
    }
    wsk.action.create(name, None, update = true, kind = Some("sequence"), expectedExitCode = MISUSE_EXIT)
  }

  it should "create, and get an action to verify parameter and annotation parsing" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "actionAnnotations"
      val file = Some(TestUtils.getTestActionFilename("hello.js"))

      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, file, annotations = getValidJSONTestArgInput, parameters = getValidJSONTestArgInput)
      }

      val stdout = wsk.action.get(name).stdout
      assert(stdout.startsWith(s"ok: got action $name\n"))

      val receivedParams = wsk.parseJsonString(stdout).fields("parameters").convertTo[JsArray].elements
      val receivedAnnots = wsk.parseJsonString(stdout).fields("annotations").convertTo[JsArray].elements
      val escapedJSONArr = getValidJSONTestArgOutput.convertTo[JsArray].elements

      for (expectedItem <- escapedJSONArr) {
        receivedParams should contain(expectedItem)
        receivedAnnots should contain(expectedItem)
      }
  }

  it should "create, and get an action to verify file parameter and annotation parsing" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "actionAnnotAndParamParsing"
      val file = Some(TestUtils.getTestActionFilename("hello.js"))
      val argInput = Some(TestUtils.getTestActionFilename("validInput1.json"))

      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, file, annotationFile = argInput, parameterFile = argInput)
      }

      val stdout = wsk.action.get(name).stdout
      assert(stdout.startsWith(s"ok: got action $name\n"))

      val receivedParams = wsk.parseJsonString(stdout).fields("parameters").convertTo[JsArray].elements
      val receivedAnnots = wsk.parseJsonString(stdout).fields("annotations").convertTo[JsArray].elements
      val escapedJSONArr = getJSONFileOutput.convertTo[JsArray].elements

      for (expectedItem <- escapedJSONArr) {
        receivedParams should contain(expectedItem)
        receivedAnnots should contain(expectedItem)
      }
  }

  it should "create an action with the proper parameter and annotation escapes" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "actionEscapes"
      val file = Some(TestUtils.getTestActionFilename("hello.js"))

      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, file, parameters = getEscapedJSONTestArgInput, annotations = getEscapedJSONTestArgInput)
      }

      val stdout = wsk.action.get(name).stdout
      assert(stdout.startsWith(s"ok: got action $name\n"))

      val receivedParams = wsk.parseJsonString(stdout).fields("parameters").convertTo[JsArray].elements
      val receivedAnnots = wsk.parseJsonString(stdout).fields("annotations").convertTo[JsArray].elements
      val escapedJSONArr = getEscapedJSONTestArgOutput.convertTo[JsArray].elements

      for (expectedItem <- escapedJSONArr) {
        receivedParams should contain(expectedItem)
        receivedAnnots should contain(expectedItem)
      }
  }

  it should "invoke an action that exits during initialization and get appropriate error" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "abort init"
      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, Some(TestUtils.getTestActionFilename("initexit.js")))
      }

      withActivation(wsk.activation, wsk.action.invoke(name)) { activation =>
        val response = activation.response
        response.result.get.fields("error") shouldBe Messages.abnormalInitialization.toJson
        response.status shouldBe ActivationResponse.messageForCode(ActivationResponse.ContainerError)
      }
  }

  it should "invoke an action that hangs during initialization and get appropriate error" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "hang init"
      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, Some(TestUtils.getTestActionFilename("initforever.js")), timeout = Some(3 seconds))
      }

      withActivation(wsk.activation, wsk.action.invoke(name)) { activation =>
        val response = activation.response
        response.result.get.fields("error") shouldBe Messages.timedoutActivation(3 seconds, true).toJson
        response.status shouldBe ActivationResponse.messageForCode(ActivationResponse.ApplicationError)
      }
  }

  it should "invoke an action that exits during run and get appropriate error" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "abort run"
      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, Some(TestUtils.getTestActionFilename("runexit.js")))
      }

      withActivation(wsk.activation, wsk.action.invoke(name)) { activation =>
        val response = activation.response
        response.result.get.fields("error") shouldBe Messages.abnormalRun.toJson
        response.status shouldBe ActivationResponse.messageForCode(ActivationResponse.ContainerError)
      }
  }

  it should "retrieve the last activation using --last flag" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val auth: Seq[String] = Seq("--auth", wskprops.authKey)
    val includeStr = "hello, undefined!"

    assetHelper.withCleaner(wsk.action, "lastName") { (action, _) =>
      wsk.action.create("lastName", defaultAction)
    }
    retry(
      {
        val lastInvoke = wsk.action.invoke("lastName")
        withActivation(wsk.activation, lastInvoke) {
          activation =>
            val lastFlag = Seq(
              (Seq("activation", "get", "publish", "--last"), activation.activationId),
              (Seq("activation", "get", "--last"), activation.activationId),
              (Seq("activation", "logs", "--last"), includeStr),
              (Seq("activation", "result", "--last"), includeStr))

            retry({
              lastFlag foreach {
                case (cmd, output) =>
                  val stdout = wsk.cli(cmd ++ wskprops.overrides ++ auth, expectedExitCode = SUCCESS_EXIT).stdout
                  stdout should include(output)
              }
            }, waitBeforeRetry = Some(500.milliseconds))

        }
      },
      waitBeforeRetry = Some(1.second),
      N = 5)
  }

  it should "ensure timestamp and stream are stripped from log lines" in withAssetCleaner(wskprops) {
    val name = "activationLogStripTest"
    val auth: Seq[String] = Seq("--auth", wskprops.authKey)

    (wp, assetHelper) =>
      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, Some(TestUtils.getTestActionFilename("log.js")))
      }

      withActivation(wsk.activation, wsk.action.invoke(name)) { activation =>
        val cmd = Seq("activation", "logs", "--strip", activation.activationId)
        val run = wsk.cli(cmd ++ wskprops.overrides ++ auth, expectedExitCode = SUCCESS_EXIT)
        run.stdout shouldBe "this is stdout\nthis is stderr\n"
      }
  }

  it should "ensure keys are not omitted from activation record" in withAssetCleaner(wskprops) {
    val name = "activationRecordTest"

    (wp, assetHelper) =>
      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, Some(TestUtils.getTestActionFilename("argCheck.js")))
      }

      val run = wsk.action.invoke(name)
      withActivation(wsk.activation, run) { activation =>
        activation.start should be > Instant.EPOCH
        activation.end should be > Instant.EPOCH
        activation.response.status shouldBe ActivationResponse.messageForCode(ActivationResponse.Success)
        activation.response.success shouldBe true
        activation.response.result shouldBe Some(JsObject())
        activation.logs shouldBe Some(List())
        activation.annotations shouldBe defined
      }
  }

  it should "write the action-path and the limits to the annotations" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "annotations"
      val memoryLimit = 512 MB
      val logLimit = 1 MB
      val timeLimit = 60 seconds

      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(
          name,
          Some(TestUtils.getTestActionFilename("helloAsync.js")),
          memory = Some(memoryLimit),
          timeout = Some(timeLimit),
          logsize = Some(logLimit))
      }

      val run = wsk.action.invoke(name, Map("payload" -> "this is a test".toJson))
      withActivation(wsk.activation, run) { activation =>
        activation.response.status shouldBe "success"
        val annotations = activation.annotations.get

        val limitsObj = JsObject(
          "key" -> JsString("limits"),
          "value" -> ActionLimits(TimeLimit(timeLimit), MemoryLimit(memoryLimit), LogLimit(logLimit)).toJson)

        val path = annotations.find {
          _.fields("key").convertTo[String] == "path"
        }.get

        path.fields("value").convertTo[String] should fullyMatch regex (s""".*/$name""")
        annotations should contain(limitsObj)
      }
  }

  it should "report error when creating an action with unknown kind" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val rr = assetHelper.withCleaner(wsk.action, "invalid kind", confirmDelete = false) { (action, name) =>
        action.create(
          name,
          Some(TestUtils.getTestActionFilename("echo.js")),
          kind = Some("foobar"),
          expectedExitCode = BAD_REQUEST)
      }
      rr.stderr should include regex "kind 'foobar' not in Set"
  }

  it should "report error when creating an action with zip but without kind" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "zipWithNoKind"
      val zippedPythonAction = Some(TestUtils.getTestActionFilename("python.zip"))
      val createResult = assetHelper.withCleaner(wsk.action, name, confirmDelete = false) { (action, _) =>
        action.create(name, zippedPythonAction, expectedExitCode = ANY_ERROR_EXIT)
      }

      createResult.stderr should include regex "requires specifying the action kind"
  }

  it should "create, and invoke an action that utilizes an invalid docker container with appropriate error" in withAssetCleaner(
    wskprops) {
    val name = "invalidDockerContainer"
    val containerName = s"bogus${Random.alphanumeric.take(16).mkString.toLowerCase}"

    (wp, assetHelper) =>
      assetHelper.withCleaner(wsk.action, name) {
        // docker name is a randomly generate string
        (action, _) =>
          action.create(name, None, docker = Some(containerName))
      }

      val run = wsk.action.invoke(name)
      withActivation(wsk.activation, run) { activation =>
        activation.response.status shouldBe ActivationResponse.messageForCode(ActivationResponse.ApplicationError)
        activation.response.result.get
          .fields("error") shouldBe s"Failed to pull container image '$containerName'.".toJson
        activation.annotations shouldBe defined
        val limits = activation.annotations.get.filter(_.fields("key").convertTo[String] == "limits")
        withClue(limits) {
          limits.length should be > 0
          limits(0).fields("value") should not be JsNull
        }
      }
  }

  it should "invoke an action using npm openwhisk" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "hello npm openwhisk"
    assetHelper.withCleaner(wsk.action, name, confirmDelete = false) { (action, _) =>
      action.create(name, Some(TestUtils.getTestActionFilename("helloOpenwhiskPackage.js")))
    }

    val run = wsk.action.invoke(name, Map("ignore_certs" -> true.toJson, "name" -> name.toJson))
    withActivation(wsk.activation, run) { activation =>
      activation.response.status shouldBe "success"
      activation.response.result shouldBe Some(JsObject("delete" -> true.toJson))
      activation.logs.get.mkString(" ") should include("action list has this many actions")
    }

    wsk.action.delete(name, expectedExitCode = TestUtils.NOT_FOUND)
  }

  it should "invoke an action receiving context properties" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val namespace = wsk.namespace.whois()
    val name = "context"
    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, Some(TestUtils.getTestActionFilename("helloContext.js")))
    }

    val start = Instant.now(Clock.systemUTC()).toEpochMilli
    val run = wsk.action.invoke(name)
    withActivation(wsk.activation, run) { activation =>
      activation.response.status shouldBe "success"
      val fields = activation.response.result.get.convertTo[Map[String, String]]
      fields("api_host") shouldBe WhiskProperties.getApiHostForAction
      fields("api_key") shouldBe wskprops.authKey
      fields("namespace") shouldBe namespace
      fields("action_name") shouldBe s"/$namespace/$name"
      fields("activation_id") shouldBe activation.activationId
      fields("deadline").toLong should be >= start
    }
  }

  it should "invoke an action successfully with options --blocking and --result" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "invokeResult"
      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, Some(TestUtils.getTestActionFilename("echo.js")))
      }
      val args = Map("hello" -> "Robert".toJson)
      val run = wsk.action.invoke(name, args, blocking = true, result = true)
      run.stdout.parseJson shouldBe args.toJson
  }

  it should "invoke an action that returns a result by the deadline" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "deadline"
      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, Some(TestUtils.getTestActionFilename("helloDeadline.js")), timeout = Some(3 seconds))
      }

      val run = wsk.action.invoke(name)
      withActivation(wsk.activation, run) { activation =>
        activation.response.status shouldBe "success"
        activation.response.result shouldBe Some(JsObject("timedout" -> true.toJson))
      }
  }

  it should "invoke an action twice, where the first times out but the second does not and should succeed" in withAssetCleaner(
    wskprops) {
    // this test issues two activations: the first is forced to time out and not return a result by its deadline (ie it does not resolve
    // its promise). The invoker should reclaim its container so that a second activation of the same action (which must happen within a
    // short period of time (seconds, not minutes) is allocated a fresh container and hence runs as expected (vs. hitting in the container
    // cache and reusing a bad container).
    (wp, assetHelper) =>
      val name = "timeout"
      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, Some(TestUtils.getTestActionFilename("helloDeadline.js")), timeout = Some(3 seconds))
      }

      val start = Instant.now(Clock.systemUTC()).toEpochMilli
      val hungRun = wsk.action.invoke(name, Map("forceHang" -> true.toJson))
      withActivation(wsk.activation, hungRun) { activation =>
        // the first action must fail with a timeout error
        activation.response.status shouldBe ActivationResponse.messageForCode(ActivationResponse.ApplicationError)
        activation.response.result shouldBe Some(
          JsObject("error" -> Messages.timedoutActivation(3 seconds, false).toJson))
      }

      // run the action again, this time without forcing it to timeout
      // it should succeed because it ran in a fresh container
      val goodRun = wsk.action.invoke(name, Map("forceHang" -> false.toJson))
      withActivation(wsk.activation, goodRun) { activation =>
        // the first action must fail with a timeout error
        activation.response.status shouldBe "success"
        activation.response.result shouldBe Some(JsObject("timedout" -> true.toJson))
      }
  }

  it should "ensure --web flags set the proper annotations" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "webaction"
    val file = Some(TestUtils.getTestActionFilename("echo.js"))

    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, file)
    }

    Seq("true", "faLse", "tRue", "nO", "yEs", "no", "raw", "NO", "Raw").foreach { flag =>
      val webEnabled = flag.toLowerCase == "true" || flag.toLowerCase == "yes"
      val rawEnabled = flag.toLowerCase == "raw"

      wsk.action.create(name, file, web = Some(flag), update = true)

      val stdout = wsk.action.get(name, fieldFilter = Some("annotations")).stdout
      assert(stdout.startsWith(s"ok: got action $name, displaying field annotations\n"))
      removeCLIHeader(stdout).parseJson shouldBe JsArray(
        JsObject("key" -> JsString("exec"), "value" -> JsString("nodejs:6")),
        JsObject("key" -> JsString("web-export"), "value" -> JsBoolean(webEnabled || rawEnabled)),
        JsObject("key" -> JsString("raw-http"), "value" -> JsBoolean(rawEnabled)),
        JsObject("key" -> JsString("final"), "value" -> JsBoolean(webEnabled || rawEnabled)))
    }
  }

  it should "ensure action update with --web flag only copies existing annotations when new annotations are not provided" in withAssetCleaner(
    wskprops) { (wp, assetHelper) =>
    val name = "webaction"
    val file = Some(TestUtils.getTestActionFilename("echo.js"))
    val createKey = "createKey"
    val createValue = JsString("createValue")
    val updateKey = "updateKey"
    val updateValue = JsString("updateValue")
    val origKey = "origKey"
    val origValue = JsString("origValue")
    val overwrittenValue = JsString("overwrittenValue")
    val createAnnots = Map(createKey -> createValue, origKey -> origValue)
    val updateAnnots = Map(updateKey -> updateValue, origKey -> overwrittenValue)

    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, file, annotations = createAnnots)
    }

    wsk.action.create(name, file, web = Some("true"), update = true)

    val existinAnnots = wsk.action.get(name, fieldFilter = Some("annotations")).stdout
    assert(existinAnnots.startsWith(s"ok: got action $name, displaying field annotations\n"))
    removeCLIHeader(existinAnnots).parseJson shouldBe JsArray(
      JsObject("key" -> JsString("web-export"), "value" -> JsBoolean(true)),
      JsObject("key" -> JsString(origKey), "value" -> origValue),
      JsObject("key" -> JsString("raw-http"), "value" -> JsBoolean(false)),
      JsObject("key" -> JsString("final"), "value" -> JsBoolean(true)),
      JsObject("key" -> JsString(createKey), "value" -> createValue),
      JsObject("key" -> JsString("exec"), "value" -> JsString("nodejs:6")))

    wsk.action.create(name, file, web = Some("true"), update = true, annotations = updateAnnots)

    val updatedAnnots = wsk.action.get(name, fieldFilter = Some("annotations")).stdout
    assert(updatedAnnots.startsWith(s"ok: got action $name, displaying field annotations\n"))
    removeCLIHeader(updatedAnnots).parseJson shouldBe JsArray(
      JsObject("key" -> JsString("web-export"), "value" -> JsBoolean(true)),
      JsObject("key" -> JsString(origKey), "value" -> overwrittenValue),
      JsObject("key" -> JsString(updateKey), "value" -> updateValue),
      JsObject("key" -> JsString("raw-http"), "value" -> JsBoolean(false)),
      JsObject("key" -> JsString("final"), "value" -> JsBoolean(true)),
      JsObject("key" -> JsString("exec"), "value" -> JsString("nodejs:6")))
  }

  it should "ensure action update creates an action with --web flag" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "webaction"
      val file = Some(TestUtils.getTestActionFilename("echo.js"))

      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, file, web = Some("true"), update = true)
      }

      val stdout = wsk.action.get(name, fieldFilter = Some("annotations")).stdout
      assert(stdout.startsWith(s"ok: got action $name, displaying field annotations\n"))
      removeCLIHeader(stdout).parseJson shouldBe JsArray(
        JsObject("key" -> JsString("web-export"), "value" -> JsBoolean(true)),
        JsObject("key" -> JsString("raw-http"), "value" -> JsBoolean(false)),
        JsObject("key" -> JsString("final"), "value" -> JsBoolean(true)),
        JsObject("key" -> JsString("exec"), "value" -> JsString("nodejs:6")))
  }

  it should "reject action create and update with invalid web flag input" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "webaction"
      val file = Some(TestUtils.getTestActionFilename("echo.js"))
      val invalidInput = "bogus"
      val errorMsg =
        s"Invalid argument '$invalidInput' for --web flag. Valid input consist of 'yes', 'true', 'raw', 'false', or 'no'."
      wsk.action.create(name, file, web = Some(invalidInput), expectedExitCode = MISUSE_EXIT).stderr should include(
        errorMsg)
      wsk.action
        .create(name, file, web = Some(invalidInput), update = true, expectedExitCode = MISUSE_EXIT)
        .stderr should include(errorMsg)
  }

  it should "invoke action while not encoding &, <, > characters" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "nonescape"
    val file = Some(TestUtils.getTestActionFilename("hello.js"))
    val nonescape = "&<>"
    val input = Map("payload" -> nonescape.toJson)
    val output = JsObject("payload" -> JsString(s"hello, $nonescape!"))

    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, file)
    }

    withActivation(wsk.activation, wsk.action.invoke(name, parameters = input)) { activation =>
      activation.response.success shouldBe true
      activation.response.result shouldBe Some(output)
      activation.logs.toList.flatten.filter(_.contains(nonescape)).length shouldBe 1
    }
  }

  it should "get an action URL" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val actionName = "action name@_-."
    val packageName = "package name@_-."
    val defaultPackageName = "default"
    val webActionName = "web action name@_-."
    val nonExistentActionName = "non-existence action"
    val packagedAction = s"$packageName/$actionName"
    val packagedWebAction = s"$packageName/$webActionName"
    val namespace = wsk.namespace.whois()
    val encodedActionName = URLEncoder.encode(actionName, StandardCharsets.UTF_8.name).replace("+", "%20")
    val encodedPackageName = URLEncoder.encode(packageName, StandardCharsets.UTF_8.name).replace("+", "%20")
    val encodedWebActionName = URLEncoder.encode(webActionName, StandardCharsets.UTF_8.name).replace("+", "%20")
    val encodedNamespace = URLEncoder.encode(namespace, StandardCharsets.UTF_8.name).replace("+", "%20")
    val actionPath = "https://%s/api/%s/namespaces/%s/actions/%s"
    val packagedActionPath = s"$actionPath/%s"
    val webActionPath = "https://%s/api/%s/web/%s/%s/%s"

    assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
      action.create(actionName, defaultAction)
    }

    assetHelper.withCleaner(wsk.action, webActionName) { (action, _) =>
      action.create(webActionName, defaultAction, web = Some("true"))
    }

    assetHelper.withCleaner(wsk.pkg, packageName) { (pkg, _) =>
      pkg.create(packageName)
    }

    assetHelper.withCleaner(wsk.action, packagedAction) { (action, _) =>
      action.create(packagedAction, defaultAction)
    }

    assetHelper.withCleaner(wsk.action, packagedWebAction) { (action, _) =>
      action.create(packagedWebAction, defaultAction, web = Some("true"))
    }

    wsk.action.get(actionName, url = Some(true)).stdout should include(
      actionPath.format(wskprops.apihost, wskprops.apiversion, encodedNamespace, encodedActionName))

    // Ensure url flag works when a field filter and summary flag are specified
    wsk.action.get(actionName, url = Some(true), fieldFilter = Some("field"), summary = true).stdout should include(
      actionPath.format(wskprops.apihost, wskprops.apiversion, encodedNamespace, encodedActionName))

    wsk.action.get(webActionName, url = Some(true)).stdout should include(
      webActionPath
        .format(wskprops.apihost, wskprops.apiversion, encodedNamespace, defaultPackageName, encodedWebActionName))

    wsk.action.get(packagedAction, url = Some(true)).stdout should include(
      packagedActionPath
        .format(wskprops.apihost, wskprops.apiversion, encodedNamespace, encodedPackageName, encodedActionName))

    wsk.action.get(packagedWebAction, url = Some(true)).stdout should include(
      webActionPath
        .format(wskprops.apihost, wskprops.apiversion, encodedNamespace, encodedPackageName, encodedWebActionName))

    wsk.action.get(nonExistentActionName, url = Some(true), expectedExitCode = NOT_FOUND)
  }
  it should "limit length of HTTP request and response bodies for --verbose" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "limitVerbose"
      val msg = "will be truncated"
      val params = Seq("-p", "bigValue", "a" * 1000)

      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, Some(TestUtils.getTestActionFilename("echo.js")))
      }

      val truncated = wsk
        .cli(Seq("action", "invoke", name, "-b", "-v", "--auth", wskprops.authKey) ++ params ++ wskprops.overrides)
        .stdout
      msg.r.findAllIn(truncated).length shouldBe 2

      val notTruncated = wsk
        .cli(Seq("action", "invoke", name, "-b", "-d", "--auth", wskprops.authKey) ++ params ++ wskprops.overrides)
        .stdout
      msg.r.findAllIn(notTruncated).length shouldBe 0
  }

  it should "denote bound and finalized action parameters for action summaries" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val nameBoundParams = "actionBoundParams"
      val nameFinalParams = "actionFinalParams"
      val paramAnnot = "paramAnnot"
      val paramOverlap = "paramOverlap"
      val paramBound = "paramBound"
      val annots = Map(
        "parameters" -> JsArray(
          JsObject("name" -> JsString(paramAnnot), "description" -> JsString("Annotated")),
          JsObject("name" -> JsString(paramOverlap), "description" -> JsString("Annotated And Bound"))))
      val annotsFinal = Map(
        "final" -> JsBoolean(true),
        "parameters" -> JsArray(
          JsObject("name" -> JsString(paramAnnot), "description" -> JsString("Annotated Parameter description")),
          JsObject("name" -> JsString(paramOverlap), "description" -> JsString("Annotated And Bound"))))
      val paramsBound = Map(paramBound -> JsString("Bound"), paramOverlap -> JsString("Bound And Annotated"))

      assetHelper.withCleaner(wsk.action, nameBoundParams) { (action, _) =>
        action.create(nameBoundParams, defaultAction, annotations = annots, parameters = paramsBound)
      }
      assetHelper.withCleaner(wsk.action, nameFinalParams) { (action, _) =>
        action.create(nameFinalParams, defaultAction, annotations = annotsFinal, parameters = paramsBound)
      }

      val stdoutBound = wsk.action.get(nameBoundParams, summary = true).stdout
      val stdoutFinal = wsk.action.get(nameFinalParams, summary = true).stdout

      stdoutBound should include(s"(parameters: $paramAnnot, *$paramBound, *$paramOverlap)")
      stdoutFinal should include(s"(parameters: $paramAnnot, **$paramBound, **$paramOverlap)")
  }

  it should "create, and get an action summary without a description and/or defined parameters" in withAssetCleaner(
    wskprops) { (wp, assetHelper) =>
    val actNameNoParams = "actionNoParams"
    val actNameNoDesc = "actionNoDesc"
    val actNameNoDescOrParams = "actionNoDescOrParams"
    val desc = "Action description"
    val descFromParamsResp = "Returns a result based on parameters"
    val annotsNoParams = Map("description" -> JsString(desc))
    val annotsNoDesc = Map(
      "parameters" -> JsArray(
        JsObject("name" -> JsString("paramName1"), "description" -> JsString("Parameter description 1")),
        JsObject("name" -> JsString("paramName2"), "description" -> JsString("Parameter description 2"))))

    assetHelper.withCleaner(wsk.action, actNameNoDesc) { (action, _) =>
      action.create(actNameNoDesc, defaultAction, annotations = annotsNoDesc)
    }
    assetHelper.withCleaner(wsk.action, actNameNoParams) { (action, _) =>
      action.create(actNameNoParams, defaultAction, annotations = annotsNoParams)
    }
    assetHelper.withCleaner(wsk.action, actNameNoDescOrParams) { (action, _) =>
      action.create(actNameNoDescOrParams, defaultAction)
    }

    val stdoutNoDesc = wsk.action.get(actNameNoDesc, summary = true).stdout
    val stdoutNoParams = wsk.action.get(actNameNoParams, summary = true).stdout
    val stdoutNoDescOrParams = wsk.action.get(actNameNoDescOrParams, summary = true).stdout
    val namespace = wsk.namespace.whois()

    stdoutNoDesc should include regex (s"(?i)action /${namespace}/${actNameNoDesc}: ${descFromParamsResp} paramName1 and paramName2\\s*\\(parameters: paramName1, paramName2\\)")
    stdoutNoParams should include regex (s"(?i)action /${namespace}/${actNameNoParams}: ${desc}\\s*\\(parameters: none defined\\)")
    stdoutNoDescOrParams should include regex (s"(?i)action /${namespace}/${actNameNoDescOrParams}\\s*\\(parameters: none defined\\)")
  }

  behavior of "Wsk packages"

  it should "create, and delete a package" in {
    val name = "createDeletePackage"
    wsk.pkg.create(name).stdout should include(s"ok: created package $name")
    wsk.pkg.delete(name).stdout should include(s"ok: deleted package $name")
  }

  it should "create, and get a package to verify parameter and annotation parsing" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "packageAnnotAndParamParsing"

      assetHelper.withCleaner(wsk.pkg, name) { (pkg, _) =>
        pkg.create(name, annotations = getValidJSONTestArgInput, parameters = getValidJSONTestArgInput)
      }

      val stdout = wsk.pkg.get(name).stdout
      assert(stdout.startsWith(s"ok: got package $name\n"))

      val receivedParams = wsk.parseJsonString(stdout).fields("parameters").convertTo[JsArray].elements
      val receivedAnnots = wsk.parseJsonString(stdout).fields("annotations").convertTo[JsArray].elements
      val escapedJSONArr = getValidJSONTestArgOutput.convertTo[JsArray].elements

      for (expectedItem <- escapedJSONArr) {
        receivedParams should contain(expectedItem)
        receivedAnnots should contain(expectedItem)
      }
  }

  it should "create, and get a package to verify file parameter and annotation parsing" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "packageAnnotAndParamFileParsing"
      val file = Some(TestUtils.getTestActionFilename("hello.js"))
      val argInput = Some(TestUtils.getTestActionFilename("validInput1.json"))

      assetHelper.withCleaner(wsk.pkg, name) { (pkg, _) =>
        pkg.create(name, annotationFile = argInput, parameterFile = argInput)
      }

      val stdout = wsk.pkg.get(name).stdout
      assert(stdout.startsWith(s"ok: got package $name\n"))

      val receivedParams = wsk.parseJsonString(stdout).fields("parameters").convertTo[JsArray].elements
      val receivedAnnots = wsk.parseJsonString(stdout).fields("annotations").convertTo[JsArray].elements
      val escapedJSONArr = getJSONFileOutput.convertTo[JsArray].elements

      for (expectedItem <- escapedJSONArr) {
        receivedParams should contain(expectedItem)
        receivedAnnots should contain(expectedItem)
      }
  }

  it should "create a package with the proper parameter and annotation escapes" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "packageEscapses"

      assetHelper.withCleaner(wsk.pkg, name) { (pkg, _) =>
        pkg.create(name, parameters = getEscapedJSONTestArgInput, annotations = getEscapedJSONTestArgInput)
      }

      val stdout = wsk.pkg.get(name).stdout
      assert(stdout.startsWith(s"ok: got package $name\n"))

      val receivedParams = wsk.parseJsonString(stdout).fields("parameters").convertTo[JsArray].elements
      val receivedAnnots = wsk.parseJsonString(stdout).fields("annotations").convertTo[JsArray].elements
      val escapedJSONArr = getEscapedJSONTestArgOutput.convertTo[JsArray].elements

      for (expectedItem <- escapedJSONArr) {
        receivedParams should contain(expectedItem)
        receivedAnnots should contain(expectedItem)
      }
  }

  it should "report conformance error accessing action as package" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "aAsP"
    val file = Some(TestUtils.getTestActionFilename("hello.js"))
    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, file)
    }

    wsk.pkg.get(name, expectedExitCode = CONFLICT).stderr should include(Messages.conformanceMessage)

    wsk.pkg.bind(name, "bogus", expectedExitCode = CONFLICT).stderr should include(Messages.requestedBindingIsNotValid)

    wsk.pkg.bind("bogus", "alsobogus", expectedExitCode = BAD_REQUEST).stderr should include(
      Messages.bindingDoesNotExist)

  }

  it should "create, and get a package summary without a description and/or parameters" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val pkgNoDesc = "pkgNoDesc"
      val pkgNoParams = "pkgNoParams"
      val pkgNoDescOrParams = "pkgNoDescOrParams"
      val pkgDesc = "Package description"
      val descFromParams = "Returns a result based on parameters"
      val namespace = wsk.namespace.whois()
      val qualpkgNoDesc = s"/${namespace}/${pkgNoDesc}"
      val qualpkgNoParams = s"/${namespace}/${pkgNoParams}"
      val qualpkgNoDescOrParams = s"/${namespace}/${pkgNoDescOrParams}"

      val pkgAnnotsNoParams = Map("description" -> JsString(pkgDesc))
      val pkgAnnotsNoDesc = Map(
        "parameters" -> JsArray(
          JsObject("name" -> JsString("paramName1"), "description" -> JsString("Parameter description 1")),
          JsObject("name" -> JsString("paramName2"), "description" -> JsString("Parameter description 2"))))

      assetHelper.withCleaner(wsk.pkg, pkgNoDesc) { (pkg, _) =>
        pkg.create(pkgNoDesc, annotations = pkgAnnotsNoDesc)
      }
      assetHelper.withCleaner(wsk.pkg, pkgNoParams) { (pkg, _) =>
        pkg.create(pkgNoParams, annotations = pkgAnnotsNoParams)
      }
      assetHelper.withCleaner(wsk.pkg, pkgNoDescOrParams) { (pkg, _) =>
        pkg.create(pkgNoDescOrParams)
      }

      val stdoutNoDescPkg = wsk.pkg.get(pkgNoDesc, summary = true).stdout
      val stdoutNoParamsPkg = wsk.pkg.get(pkgNoParams, summary = true).stdout
      val stdoutNoDescOrParams = wsk.pkg.get(pkgNoDescOrParams, summary = true).stdout

      stdoutNoDescPkg should include regex (s"(?i)package ${qualpkgNoDesc}: ${descFromParams} paramName1 and paramName2\\s*\\(parameters: paramName1, paramName2\\)")
      stdoutNoParamsPkg should include regex (s"(?i)package ${qualpkgNoParams}: ${pkgDesc}\\s*\\(parameters: none defined\\)")
      stdoutNoDescOrParams should include regex (s"(?i)package ${qualpkgNoDescOrParams}\\s*\\(parameters: none defined\\)")
  }

  it should "denote bound package parameters for package summaries" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val pkgBoundParams = "pkgBoundParams"
    val pkgParamAnnot = "pkgParamAnnot"
    val pkgParamOverlap = "pkgParamOverlap"
    val pkgParamBound = "pkgParamBound"
    val pkgAnnots = Map(
      "parameters" -> JsArray(
        JsObject("name" -> JsString(pkgParamAnnot), "description" -> JsString("Annotated")),
        JsObject("name" -> JsString(pkgParamOverlap), "description" -> JsString("Annotated And Bound"))))
    val pkgParamsBound = Map(pkgParamBound -> JsString("Bound"), pkgParamOverlap -> JsString("Bound And Annotated"))

    assetHelper.withCleaner(wsk.pkg, pkgBoundParams) { (pkg, _) =>
      pkg.create(pkgBoundParams, annotations = pkgAnnots, parameters = pkgParamsBound)
    }

    val pkgStdoutBound = wsk.pkg.get(pkgBoundParams, summary = true).stdout

    pkgStdoutBound should include(s"(parameters: $pkgParamAnnot, *$pkgParamBound, *$pkgParamOverlap)")
  }

  behavior of "Wsk triggers"

  it should "create, and get a trigger to verify parameter and annotation parsing" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "triggerAnnotAndParamParsing"

      assetHelper.withCleaner(wsk.trigger, name) { (trigger, _) =>
        trigger.create(name, annotations = getValidJSONTestArgInput, parameters = getValidJSONTestArgInput)
      }

      val stdout = wsk.trigger.get(name).stdout
      assert(stdout.startsWith(s"ok: got trigger $name\n"))

      val receivedParams = wsk.parseJsonString(stdout).fields("parameters").convertTo[JsArray].elements
      val receivedAnnots = wsk.parseJsonString(stdout).fields("annotations").convertTo[JsArray].elements
      val escapedJSONArr = getValidJSONTestArgOutput.convertTo[JsArray].elements

      for (expectedItem <- escapedJSONArr) {
        receivedParams should contain(expectedItem)
        receivedAnnots should contain(expectedItem)
      }
  }

  it should "create, and get a trigger to verify file parameter and annotation parsing" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "triggerAnnotAndParamFileParsing"
      val file = Some(TestUtils.getTestActionFilename("hello.js"))
      val argInput = Some(TestUtils.getTestActionFilename("validInput1.json"))

      assetHelper.withCleaner(wsk.trigger, name) { (trigger, _) =>
        trigger.create(name, annotationFile = argInput, parameterFile = argInput)
      }

      val stdout = wsk.trigger.get(name).stdout
      assert(stdout.startsWith(s"ok: got trigger $name\n"))

      val receivedParams = wsk.parseJsonString(stdout).fields("parameters").convertTo[JsArray].elements
      val receivedAnnots = wsk.parseJsonString(stdout).fields("annotations").convertTo[JsArray].elements
      val escapedJSONArr = getJSONFileOutput.convertTo[JsArray].elements

      for (expectedItem <- escapedJSONArr) {
        receivedParams should contain(expectedItem)
        receivedAnnots should contain(expectedItem)
      }
  }

  it should "display a trigger summary when --summary flag is used with 'wsk trigger get'" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val triggerName = "mySummaryTrigger"
      assetHelper.withCleaner(wsk.trigger, triggerName, confirmDelete = false) { (trigger, name) =>
        trigger.create(name)
      }

      // Summary namespace should match one of the allowable namespaces (typically 'guest')
      val ns = wsk.namespace.whois()
      val stdout = wsk.trigger.get(triggerName, summary = true).stdout
      stdout should include regex (s"(?i)trigger\\s+/$ns/$triggerName")
  }

  it should "create a trigger with the proper parameter and annotation escapes" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "triggerEscapes"

      assetHelper.withCleaner(wsk.trigger, name) { (trigger, _) =>
        trigger.create(name, parameters = getEscapedJSONTestArgInput, annotations = getEscapedJSONTestArgInput)
      }

      val stdout = wsk.trigger.get(name).stdout
      assert(stdout.startsWith(s"ok: got trigger $name\n"))

      val receivedParams = wsk.parseJsonString(stdout).fields("parameters").convertTo[JsArray].elements
      val receivedAnnots = wsk.parseJsonString(stdout).fields("annotations").convertTo[JsArray].elements
      val escapedJSONArr = getEscapedJSONTestArgOutput.convertTo[JsArray].elements

      for (expectedItem <- escapedJSONArr) {
        receivedParams should contain(expectedItem)
        receivedAnnots should contain(expectedItem)
      }
  }

  it should "not create a trigger when feed fails to initialize" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    assetHelper.withCleaner(wsk.trigger, "badfeed", confirmDelete = false) { (trigger, name) =>
      trigger.create(name, feed = Some(s"bogus"), expectedExitCode = ANY_ERROR_EXIT).exitCode should equal(NOT_FOUND)
      trigger.get(name, expectedExitCode = NOT_FOUND)

      trigger.create(name, feed = Some(s"bogus/feed"), expectedExitCode = ANY_ERROR_EXIT).exitCode should equal(
        NOT_FOUND)
      trigger.get(name, expectedExitCode = NOT_FOUND)
    }
  }

  it should "denote bound trigger parameters for trigger summaries" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val trgBoundParams = "trgBoundParams"
    val trgParamAnnot = "trgParamAnnot"
    val trgParamOverlap = "trgParamOverlap"
    val trgParamBound = "trgParamBound"
    val trgAnnots = Map(
      "parameters" -> JsArray(
        JsObject("name" -> JsString(trgParamAnnot), "description" -> JsString("Annotated")),
        JsObject("name" -> JsString(trgParamOverlap), "description" -> JsString("Annotated And Bound"))))
    val trgParamsBound = Map(trgParamBound -> JsString("Bound"), trgParamOverlap -> JsString("Bound And Annotated"))

    assetHelper.withCleaner(wsk.trigger, trgBoundParams) { (trigger, _) =>
      trigger.create(trgBoundParams, annotations = trgAnnots, parameters = trgParamsBound)
    }

    val trgStdoutBound = wsk.trigger.get(trgBoundParams, summary = true).stdout

    trgStdoutBound should include(s"(parameters: $trgParamAnnot, *$trgParamBound, *$trgParamOverlap)")
  }

  it should "create, and get a trigger summary without a description and/or parameters" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val trgNoDesc = "trgNoDesc"
      val trgNoParams = "trgNoParams"
      val trgNoDescOrParams = "trgNoDescOrParams"
      val trgDesc = "Package description"
      val descFromParams = "Returns a result based on parameters"
      val namespace = wsk.namespace.whois()
      val qualtrgNoDesc = s"/${namespace}/${trgNoDesc}"
      val qualtrgNoParams = s"/${namespace}/${trgNoParams}"
      val qualtrgNoDescOrParams = s"/${namespace}/${trgNoDescOrParams}"

      val trgAnnotsNoParams = Map("description" -> JsString(trgDesc))
      val trgAnnotsNoDesc = Map(
        "parameters" -> JsArray(
          JsObject("name" -> JsString("paramName1"), "description" -> JsString("Parameter description 1")),
          JsObject("name" -> JsString("paramName2"), "description" -> JsString("Parameter description 2"))))

      assetHelper.withCleaner(wsk.trigger, trgNoDesc) { (trigger, _) =>
        trigger.create(trgNoDesc, annotations = trgAnnotsNoDesc)
      }
      assetHelper.withCleaner(wsk.trigger, trgNoParams) { (trigger, _) =>
        trigger.create(trgNoParams, annotations = trgAnnotsNoParams)
      }
      assetHelper.withCleaner(wsk.trigger, trgNoDescOrParams) { (trigger, _) =>
        trigger.create(trgNoDescOrParams)
      }

      val stdoutNoDescPkg = wsk.trigger.get(trgNoDesc, summary = true).stdout
      val stdoutNoParamsPkg = wsk.trigger.get(trgNoParams, summary = true).stdout
      val stdoutNoDescOrParams = wsk.trigger.get(trgNoDescOrParams, summary = true).stdout

      stdoutNoDescPkg should include regex (s"(?i)trigger ${qualtrgNoDesc}: ${descFromParams} paramName1 and paramName2\\s*\\(parameters: paramName1, paramName2\\)")
      stdoutNoParamsPkg should include regex (s"(?i)trigger ${qualtrgNoParams}: ${trgDesc}\\s*\\(parameters: none defined\\)")
      stdoutNoDescOrParams should include regex (s"(?i)trigger ${qualtrgNoDescOrParams}\\s*\\(parameters: none defined\\)")
  }

  behavior of "Wsk entity list formatting"

  it should "create, and list a package with a long name" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "x" * 70
    assetHelper.withCleaner(wsk.pkg, name) { (pkg, _) =>
      pkg.create(name)
    }
    retry({
      wsk.pkg.list().stdout should include(s"$name private")
    }, 5, Some(1 second))
  }

  it should "create, and list an action with a long name" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "x" * 70
    val file = Some(TestUtils.getTestActionFilename("hello.js"))
    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, file)
    }
    retry({
      wsk.action.list().stdout should include(s"$name private nodejs")
    }, 5, Some(1 second))
  }

  it should "create, and list a trigger with a long name" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "x" * 70
    assetHelper.withCleaner(wsk.trigger, name) { (trigger, _) =>
      trigger.create(name)
    }
    retry({
      wsk.trigger.list().stdout should include(s"$name private")
    }, 5, Some(1 second))
  }

  it should "create, and list a rule with a long name" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val ruleName = "x" * 70
    val triggerName = "listRulesTrigger"
    val actionName = "listRulesAction";
    assetHelper.withCleaner(wsk.trigger, triggerName) { (trigger, name) =>
      trigger.create(name)
    }
    assetHelper.withCleaner(wsk.action, actionName) { (action, name) =>
      action.create(name, defaultAction)
    }
    assetHelper.withCleaner(wsk.rule, ruleName) { (rule, name) =>
      rule.create(name, trigger = triggerName, action = actionName)
    }
    retry({
      wsk.rule.list().stdout should include(s"$ruleName private")
    }, 5, Some(1 second))
  }

  it should "return a list of alphabetized actions" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    // Declare 4 actions, create them out of alphabetical order
    val actionName = "actionAlphaTest"
    for (i <- 1 to 3) {
      val name = s"$actionName$i"
      assetHelper.withCleaner(wsk.action, name) { (action, name) =>
        action.create(name, defaultAction)
      }
    }
    retry({
      val original = wsk.action.list(nameSort = Some(true)).stdout
      // Create list with action names in correct order
      val scalaSorted = List(s"${actionName}1", s"${actionName}2", s"${actionName}3")
      // Filter out everything not previously created
      val regex = s"${actionName}[1-3]".r
      // Retrieve action names into list as found in original
      val list = (regex.findAllMatchIn(original)).toList
      scalaSorted.toString shouldEqual list.toString
    }, 5, Some(1 second))
  }

  it should "return an alphabetized list with default package actions on top" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      // Declare 4 actions, create them out of alphabetical order
      val actionName = "actionPackageAlphaTest"
      val packageName = "packageAlphaTest"
      assetHelper.withCleaner(wsk.action, actionName) { (action, actionName) =>
        action.create(actionName, defaultAction)
      }
      assetHelper.withCleaner(wsk.pkg, packageName) { (pkg, packageName) =>
        pkg.create(packageName)
      }
      for (i <- 1 to 3) {
        val name = s"${packageName}/${actionName}$i"
        assetHelper.withCleaner(wsk.action, name) { (action, name) =>
          action.create(name, defaultAction)
        }
      }
      retry(
        {
          val original = wsk.action.list(nameSort = Some(true)).stdout
          // Create list with action names in correct order
          val scalaSorted = List(
            s"$actionName",
            s"${packageName}/${actionName}1",
            s"${packageName}/${actionName}2",
            s"${packageName}/${actionName}3")
          // Filter out everything not previously created
          val regexNoPackage = s"$actionName".r
          val regexWithPackage = s"${packageName}/${actionName}[1-3]".r
          // Retrieve action names into list as found in original
          val list = regexNoPackage.findFirstIn(original).get :: (regexWithPackage.findAllMatchIn(original)).toList
          scalaSorted.toString shouldEqual list.toString
        },
        5,
        Some(1 second))
  }

  it should "return a list of alphabetized packages" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    // Declare 3 packages, create them out of alphabetical order
    val packageName = "pkgAlphaTest"
    for (i <- 1 to 3) {
      val name = s"$packageName$i"
      assetHelper.withCleaner(wsk.pkg, name) { (pkg, name) =>
        pkg.create(name)
      }
    }
    retry({
      val original = wsk.pkg.list(nameSort = Some(true)).stdout
      // Create list with package names in correct order
      val scalaSorted = List(s"${packageName}1", s"${packageName}2", s"${packageName}3")
      // Filter out everything not previously created
      val regex = s"${packageName}[1-3]".r
      // Retrieve package names into list as found in original
      val list = (regex.findAllMatchIn(original)).toList
      scalaSorted.toString shouldEqual list.toString
    }, 5, Some(1 second))
  }

  it should "return a list of alphabetized triggers" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    // Declare 4 triggers, create them out of alphabetical order
    val triggerName = "triggerAlphaTest"
    for (i <- 1 to 3) {
      val name = s"$triggerName$i"
      assetHelper.withCleaner(wsk.trigger, name) { (trigger, name) =>
        trigger.create(name)
      }
    }
    retry({
      val original = wsk.trigger.list(nameSort = Some(true)).stdout
      // Create list with trigger names in correct order
      val scalaSorted = List(s"${triggerName}1", s"${triggerName}2", s"${triggerName}3")
      // Filter out everything not previously created
      val regex = s"${triggerName}[1-3]".r
      // Retrieve trigger names into list as found in original
      val list = (regex.findAllMatchIn(original)).toList
      scalaSorted.toString shouldEqual list.toString
    }, 5, Some(1 second))
  }

  it should "return a list of alphabetized rules" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    // Declare a trigger and an action for the purposes of creating rules
    val triggerName = "listRulesTrigger"
    val actionName = "listRulesAction"

    assetHelper.withCleaner(wsk.trigger, triggerName) { (trigger, name) =>
      trigger.create(name)
    }
    assetHelper.withCleaner(wsk.action, actionName) { (action, name) =>
      action.create(name, defaultAction)
    }
    // Declare 3 rules, create them out of alphabetical order
    val ruleName = "ruleAlphaTest"
    for (i <- 1 to 3) {
      val name = s"$ruleName$i"
      assetHelper.withCleaner(wsk.rule, name) { (rule, name) =>
        rule.create(name, trigger = triggerName, action = actionName)
      }
    }
    retry({
      val original = wsk.rule.list(nameSort = Some(true)).stdout
      // Create list with rule names in correct order
      val scalaSorted = List(s"${ruleName}1", s"${ruleName}2", s"${ruleName}3")
      // Filter out everything not previously created
      val regex = s"${ruleName}[1-3]".r
      // Retrieve rule names into list as found in original
      val list = (regex.findAllMatchIn(original)).toList
      scalaSorted.toString shouldEqual list.toString
    })
  }

  behavior of "Wsk params and annotations"

  it should "reject commands that are executed with invalid JSON for annotations and parameters" in {
    val invalidJSONInputs = getInvalidJSONInput
    val invalidJSONFiles = Seq(
      TestUtils.getTestActionFilename("malformed.js"),
      TestUtils.getTestActionFilename("invalidInput1.json"),
      TestUtils.getTestActionFilename("invalidInput2.json"),
      TestUtils.getTestActionFilename("invalidInput3.json"),
      TestUtils.getTestActionFilename("invalidInput4.json"))
    val paramCmds = Seq(
      Seq("action", "create", "actionName", TestUtils.getTestActionFilename("hello.js")),
      Seq("action", "update", "actionName", TestUtils.getTestActionFilename("hello.js")),
      Seq("action", "invoke", "actionName"),
      Seq("package", "create", "packageName"),
      Seq("package", "update", "packageName"),
      Seq("package", "bind", "packageName", "boundPackageName"),
      Seq("trigger", "create", "triggerName"),
      Seq("trigger", "update", "triggerName"),
      Seq("trigger", "fire", "triggerName"))
    val annotCmds = Seq(
      Seq("action", "create", "actionName", TestUtils.getTestActionFilename("hello.js")),
      Seq("action", "update", "actionName", TestUtils.getTestActionFilename("hello.js")),
      Seq("package", "create", "packageName"),
      Seq("package", "update", "packageName"),
      Seq("package", "bind", "packageName", "boundPackageName"),
      Seq("trigger", "create", "triggerName"),
      Seq("trigger", "update", "triggerName"))

    for (cmd <- paramCmds) {
      for (invalid <- invalidJSONInputs) {
        wsk
          .cli(cmd ++ Seq("-p", "key", invalid) ++ wskprops.overrides, expectedExitCode = ERROR_EXIT)
          .stderr should include("Invalid parameter argument")
      }

      for (invalid <- invalidJSONFiles) {
        wsk.cli(cmd ++ Seq("-P", invalid) ++ wskprops.overrides, expectedExitCode = ERROR_EXIT).stderr should include(
          "Invalid parameter argument")

      }
    }

    for (cmd <- annotCmds) {
      for (invalid <- invalidJSONInputs) {
        wsk
          .cli(cmd ++ Seq("-a", "key", invalid) ++ wskprops.overrides, expectedExitCode = ERROR_EXIT)
          .stderr should include("Invalid annotation argument")
      }

      for (invalid <- invalidJSONFiles) {
        wsk.cli(cmd ++ Seq("-A", invalid) ++ wskprops.overrides, expectedExitCode = ERROR_EXIT).stderr should include(
          "Invalid annotation argument")
      }
    }
  }

  it should "reject commands that are executed with a missing or invalid parameter or annotation file" in {
    val emptyFile = TestUtils.getTestActionFilename("emtpy.js")
    val missingFile = "notafile"
    val emptyFileMsg = s"File '$emptyFile' is not a valid file or it does not exist"
    val missingFileMsg = s"File '$missingFile' is not a valid file or it does not exist"
    val invalidArgs = Seq(
      (
        Seq("action", "create", "actionName", TestUtils.getTestActionFilename("hello.js"), "-P", emptyFile),
        emptyFileMsg),
      (
        Seq("action", "update", "actionName", TestUtils.getTestActionFilename("hello.js"), "-P", emptyFile),
        emptyFileMsg),
      (Seq("action", "invoke", "actionName", "-P", emptyFile), emptyFileMsg),
      (Seq("action", "create", "actionName", "-P", emptyFile), emptyFileMsg),
      (Seq("action", "update", "actionName", "-P", emptyFile), emptyFileMsg),
      (Seq("action", "invoke", "actionName", "-P", emptyFile), emptyFileMsg),
      (Seq("package", "create", "packageName", "-P", emptyFile), emptyFileMsg),
      (Seq("package", "update", "packageName", "-P", emptyFile), emptyFileMsg),
      (Seq("package", "bind", "packageName", "boundPackageName", "-P", emptyFile), emptyFileMsg),
      (Seq("package", "create", "packageName", "-P", emptyFile), emptyFileMsg),
      (Seq("package", "update", "packageName", "-P", emptyFile), emptyFileMsg),
      (Seq("package", "bind", "packageName", "boundPackageName", "-P", emptyFile), emptyFileMsg),
      (Seq("trigger", "create", "triggerName", "-P", emptyFile), emptyFileMsg),
      (Seq("trigger", "update", "triggerName", "-P", emptyFile), emptyFileMsg),
      (Seq("trigger", "fire", "triggerName", "-P", emptyFile), emptyFileMsg),
      (Seq("trigger", "create", "triggerName", "-P", emptyFile), emptyFileMsg),
      (Seq("trigger", "update", "triggerName", "-P", emptyFile), emptyFileMsg),
      (Seq("trigger", "fire", "triggerName", "-P", emptyFile), emptyFileMsg),
      (
        Seq("action", "create", "actionName", TestUtils.getTestActionFilename("hello.js"), "-A", missingFile),
        missingFileMsg),
      (
        Seq("action", "update", "actionName", TestUtils.getTestActionFilename("hello.js"), "-A", missingFile),
        missingFileMsg),
      (Seq("action", "invoke", "actionName", "-A", missingFile), missingFileMsg),
      (Seq("action", "create", "actionName", "-A", missingFile), missingFileMsg),
      (Seq("action", "update", "actionName", "-A", missingFile), missingFileMsg),
      (Seq("action", "invoke", "actionName", "-A", missingFile), missingFileMsg),
      (Seq("package", "create", "packageName", "-A", missingFile), missingFileMsg),
      (Seq("package", "update", "packageName", "-A", missingFile), missingFileMsg),
      (Seq("package", "bind", "packageName", "boundPackageName", "-A", missingFile), missingFileMsg),
      (Seq("package", "create", "packageName", "-A", missingFile), missingFileMsg),
      (Seq("package", "update", "packageName", "-A", missingFile), missingFileMsg),
      (Seq("package", "bind", "packageName", "boundPackageName", "-A", missingFile), missingFileMsg),
      (Seq("trigger", "create", "triggerName", "-A", missingFile), missingFileMsg),
      (Seq("trigger", "update", "triggerName", "-A", missingFile), missingFileMsg),
      (Seq("trigger", "fire", "triggerName", "-A", missingFile), missingFileMsg),
      (Seq("trigger", "create", "triggerName", "-A", missingFile), missingFileMsg),
      (Seq("trigger", "update", "triggerName", "-A", missingFile), missingFileMsg),
      (Seq("trigger", "fire", "triggerName", "-A", missingFile), missingFileMsg))

    invalidArgs foreach {
      case (cmd, err) =>
        val stderr = wsk.cli(cmd, expectedExitCode = MISUSE_EXIT).stderr
        stderr should include(err)
        stderr should include("Run 'wsk --help' for usage.")
    }
  }

  it should "reject commands that are executed with not enough param or annot arguments" in {
    val invalidParamMsg = "Arguments for '-p' must be a key/value pair"
    val invalidAnnotMsg = "Arguments for '-a' must be a key/value pair"
    val invalidParamFileMsg = "An argument must be provided for '-P'"
    val invalidAnnotFileMsg = "An argument must be provided for '-A'"
    val invalidArgs = Seq(
      (Seq("action", "create", "actionName", "-p"), invalidParamMsg),
      (Seq("action", "create", "actionName", "-p", "key"), invalidParamMsg),
      (Seq("action", "create", "actionName", "-P"), invalidParamFileMsg),
      (Seq("action", "update", "actionName", "-p"), invalidParamMsg),
      (Seq("action", "update", "actionName", "-p", "key"), invalidParamMsg),
      (Seq("action", "update", "actionName", "-P"), invalidParamFileMsg),
      (Seq("action", "invoke", "actionName", "-p"), invalidParamMsg),
      (Seq("action", "invoke", "actionName", "-p", "key"), invalidParamMsg),
      (Seq("action", "invoke", "actionName", "-P"), invalidParamFileMsg),
      (Seq("action", "create", "actionName", "-a"), invalidAnnotMsg),
      (Seq("action", "create", "actionName", "-a", "key"), invalidAnnotMsg),
      (Seq("action", "create", "actionName", "-A"), invalidAnnotFileMsg),
      (Seq("action", "update", "actionName", "-a"), invalidAnnotMsg),
      (Seq("action", "update", "actionName", "-a", "key"), invalidAnnotMsg),
      (Seq("action", "update", "actionName", "-A"), invalidAnnotFileMsg),
      (Seq("action", "invoke", "actionName", "-a"), invalidAnnotMsg),
      (Seq("action", "invoke", "actionName", "-a", "key"), invalidAnnotMsg),
      (Seq("action", "invoke", "actionName", "-A"), invalidAnnotFileMsg),
      (Seq("package", "create", "packageName", "-p"), invalidParamMsg),
      (Seq("package", "create", "packageName", "-p", "key"), invalidParamMsg),
      (Seq("package", "create", "packageName", "-P"), invalidParamFileMsg),
      (Seq("package", "update", "packageName", "-p"), invalidParamMsg),
      (Seq("package", "update", "packageName", "-p", "key"), invalidParamMsg),
      (Seq("package", "update", "packageName", "-P"), invalidParamFileMsg),
      (Seq("package", "bind", "packageName", "boundPackageName", "-p"), invalidParamMsg),
      (Seq("package", "bind", "packageName", "boundPackageName", "-p", "key"), invalidParamMsg),
      (Seq("package", "bind", "packageName", "boundPackageName", "-P"), invalidParamFileMsg),
      (Seq("package", "create", "packageName", "-a"), invalidAnnotMsg),
      (Seq("package", "create", "packageName", "-a", "key"), invalidAnnotMsg),
      (Seq("package", "create", "packageName", "-A"), invalidAnnotFileMsg),
      (Seq("package", "update", "packageName", "-a"), invalidAnnotMsg),
      (Seq("package", "update", "packageName", "-a", "key"), invalidAnnotMsg),
      (Seq("package", "update", "packageName", "-A"), invalidAnnotFileMsg),
      (Seq("package", "bind", "packageName", "boundPackageName", "-a"), invalidAnnotMsg),
      (Seq("package", "bind", "packageName", "boundPackageName", "-a", "key"), invalidAnnotMsg),
      (Seq("package", "bind", "packageName", "boundPackageName", "-A"), invalidAnnotFileMsg),
      (Seq("trigger", "create", "triggerName", "-p"), invalidParamMsg),
      (Seq("trigger", "create", "triggerName", "-p", "key"), invalidParamMsg),
      (Seq("trigger", "create", "triggerName", "-P"), invalidParamFileMsg),
      (Seq("trigger", "update", "triggerName", "-p"), invalidParamMsg),
      (Seq("trigger", "update", "triggerName", "-p", "key"), invalidParamMsg),
      (Seq("trigger", "update", "triggerName", "-P"), invalidParamFileMsg),
      (Seq("trigger", "fire", "triggerName", "-p"), invalidParamMsg),
      (Seq("trigger", "fire", "triggerName", "-p", "key"), invalidParamMsg),
      (Seq("trigger", "fire", "triggerName", "-P"), invalidParamFileMsg),
      (Seq("trigger", "create", "triggerName", "-a"), invalidAnnotMsg),
      (Seq("trigger", "create", "triggerName", "-a", "key"), invalidAnnotMsg),
      (Seq("trigger", "create", "triggerName", "-A"), invalidAnnotFileMsg),
      (Seq("trigger", "update", "triggerName", "-a"), invalidAnnotMsg),
      (Seq("trigger", "update", "triggerName", "-a", "key"), invalidAnnotMsg),
      (Seq("trigger", "update", "triggerName", "-A"), invalidAnnotFileMsg),
      (Seq("trigger", "fire", "triggerName", "-a"), invalidAnnotMsg),
      (Seq("trigger", "fire", "triggerName", "-a", "key"), invalidAnnotMsg),
      (Seq("trigger", "fire", "triggerName", "-A"), invalidAnnotFileMsg))

    invalidArgs foreach {
      case (cmd, err) =>
        val stderr = wsk.cli(cmd, expectedExitCode = ERROR_EXIT).stderr
        stderr should include(err)
        stderr should include("Run 'wsk --help' for usage.")
    }
  }

  behavior of "Wsk invalid argument handling"

  it should "reject commands that are executed with invalid arguments" in {
    val invalidArgsMsg = "error: Invalid argument(s)"
    val tooFewArgsMsg = invalidArgsMsg + "."
    val tooManyArgsMsg = invalidArgsMsg + ": "
    val actionNameActionReqMsg = "An action name and code artifact are required."
    val actionNameReqMsg = "An action name is required."
    val actionOptMsg = "A code artifact is optional."
    val packageNameReqMsg = "A package name is required."
    val packageNameBindingReqMsg = "A package name and binding name are required."
    val ruleNameReqMsg = "A rule name is required."
    val ruleTriggerActionReqMsg = "A rule, trigger and action name are required."
    val activationIdReq = "An activation ID is required."
    val triggerNameReqMsg = "A trigger name is required."
    val optNamespaceMsg = "An optional namespace is the only valid argument."
    val optPayloadMsg = "A payload is optional."
    val noArgsReqMsg = "No arguments are required."
    val invalidArg = "invalidArg"
    val apiCreateReqMsg =
      "Specify a swagger file or specify an API base path with an API path, an API verb, and an action name."
    val apiGetReqMsg = "An API base path or API name is required."
    val apiDeleteReqMsg =
      "An API base path or API name is required.  An optional API relative path and operation may also be provided."
    val apiListReqMsg = "Optional parameters are: API base path (or API name), API relative path and operation."
    val invalidShared = s"Cannot use value '$invalidArg' for shared"
    val entityNameMsg =
      s"An entity name, '$invalidArg', was provided instead of a namespace. Valid namespaces are of the following format: /NAMESPACE."
    val invalidArgs = Seq(
      (Seq("api", "create"), s"${tooFewArgsMsg} ${apiCreateReqMsg}"),
      (
        Seq("api", "create", "/basepath", "/path", "GET", "action", invalidArg),
        s"${tooManyArgsMsg}${invalidArg}. ${apiCreateReqMsg}"),
      (Seq("api", "get"), s"${tooFewArgsMsg} ${apiGetReqMsg}"),
      (Seq("api", "get", "/basepath", invalidArg), s"${tooManyArgsMsg}${invalidArg}. ${apiGetReqMsg}"),
      (Seq("api", "delete"), s"${tooFewArgsMsg} ${apiDeleteReqMsg}"),
      (
        Seq("api", "delete", "/basepath", "/path", "GET", invalidArg),
        s"${tooManyArgsMsg}${invalidArg}. ${apiDeleteReqMsg}"),
      (
        Seq("api", "list", "/basepath", "/path", "GET", invalidArg),
        s"${tooManyArgsMsg}${invalidArg}. ${apiListReqMsg}"),
      (Seq("action", "create"), s"${tooFewArgsMsg} ${actionNameActionReqMsg}"),
      (Seq("action", "create", "someAction"), s"${tooFewArgsMsg} ${actionNameActionReqMsg}"),
      (Seq("action", "create", "actionName", "artifactName", invalidArg), s"${tooManyArgsMsg}${invalidArg}."),
      (Seq("action", "update"), s"${tooFewArgsMsg} ${actionNameReqMsg} ${actionOptMsg}"),
      (
        Seq("action", "update", "actionName", "artifactName", invalidArg),
        s"${tooManyArgsMsg}${invalidArg}. ${actionNameReqMsg} ${actionOptMsg}"),
      (Seq("action", "delete"), s"${tooFewArgsMsg} ${actionNameReqMsg}"),
      (Seq("action", "delete", "actionName", invalidArg), s"${tooManyArgsMsg}${invalidArg}."),
      (Seq("action", "get"), s"${tooFewArgsMsg} ${actionNameReqMsg}"),
      (Seq("action", "get", "actionName", "namespace", invalidArg), s"${tooManyArgsMsg}${invalidArg}."),
      (Seq("action", "list", "namespace", invalidArg), s"${tooManyArgsMsg}${invalidArg}. ${optNamespaceMsg}"),
      (Seq("action", "invoke"), s"${tooFewArgsMsg} ${actionNameReqMsg}"),
      (Seq("action", "invoke", "actionName", invalidArg), s"${tooManyArgsMsg}${invalidArg}."),
      (Seq("activation", "list", "namespace", invalidArg), s"${tooManyArgsMsg}${invalidArg}. ${optNamespaceMsg}"),
      (Seq("activation", "get"), s"${tooFewArgsMsg} ${activationIdReq}"),
      (Seq("activation", "get", "activationID", "namespace", invalidArg), s"${tooManyArgsMsg}${invalidArg}."),
      (Seq("activation", "logs"), s"${tooFewArgsMsg} ${activationIdReq}"),
      (Seq("activation", "logs", "activationID", invalidArg), s"${tooManyArgsMsg}${invalidArg}."),
      (Seq("activation", "result"), s"${tooFewArgsMsg} ${activationIdReq}"),
      (Seq("activation", "result", "activationID", invalidArg), s"${tooManyArgsMsg}${invalidArg}."),
      (Seq("activation", "poll", "activationID", invalidArg), s"${tooManyArgsMsg}${invalidArg}. ${optNamespaceMsg}"),
      (Seq("namespace", "list", invalidArg), s"${tooManyArgsMsg}${invalidArg}. ${noArgsReqMsg}"),
      (Seq("namespace", "get", "namespace", invalidArg), s"${tooManyArgsMsg}${invalidArg}. ${optNamespaceMsg}"),
      (Seq("package", "create"), s"${tooFewArgsMsg} ${packageNameReqMsg}"),
      (Seq("package", "create", "packageName", invalidArg), s"${tooManyArgsMsg}${invalidArg}."),
      (Seq("package", "create", "packageName", "--shared", invalidArg), invalidShared),
      (Seq("package", "update"), s"${tooFewArgsMsg} ${packageNameReqMsg}"),
      (Seq("package", "update", "packageName", invalidArg), s"${tooManyArgsMsg}${invalidArg}."),
      (Seq("package", "update", "packageName", "--shared", invalidArg), invalidShared),
      (Seq("package", "get"), s"${tooFewArgsMsg} ${packageNameReqMsg}"),
      (Seq("package", "get", "packageName", "namespace", invalidArg), s"${tooManyArgsMsg}${invalidArg}."),
      (Seq("package", "bind"), s"${tooFewArgsMsg} ${packageNameBindingReqMsg}"),
      (Seq("package", "bind", "packageName"), s"${tooFewArgsMsg} ${packageNameBindingReqMsg}"),
      (Seq("package", "bind", "packageName", "bindingName", invalidArg), s"${tooManyArgsMsg}${invalidArg}."),
      (Seq("package", "list", "namespace", invalidArg), s"${tooManyArgsMsg}${invalidArg}. ${optNamespaceMsg}"),
      (Seq("package", "list", invalidArg), entityNameMsg),
      (Seq("package", "delete"), s"${tooFewArgsMsg} ${packageNameReqMsg}"),
      (Seq("package", "delete", "namespace", invalidArg), s"${tooManyArgsMsg}${invalidArg}."),
      (Seq("package", "refresh", "namespace", invalidArg), s"${tooManyArgsMsg}${invalidArg}. ${optNamespaceMsg}"),
      (Seq("package", "refresh", invalidArg), entityNameMsg),
      (Seq("rule", "enable"), s"${tooFewArgsMsg} ${ruleNameReqMsg}"),
      (Seq("rule", "enable", "ruleName", invalidArg), s"${tooManyArgsMsg}${invalidArg}."),
      (Seq("rule", "disable"), s"${tooFewArgsMsg} ${ruleNameReqMsg}"),
      (Seq("rule", "disable", "ruleName", invalidArg), s"${tooManyArgsMsg}${invalidArg}."),
      (Seq("rule", "status"), s"${tooFewArgsMsg} ${ruleNameReqMsg}"),
      (Seq("rule", "status", "ruleName", invalidArg), s"${tooManyArgsMsg}${invalidArg}."),
      (Seq("rule", "create"), s"${tooFewArgsMsg} ${ruleTriggerActionReqMsg}"),
      (Seq("rule", "create", "ruleName"), s"${tooFewArgsMsg} ${ruleTriggerActionReqMsg}"),
      (Seq("rule", "create", "ruleName", "triggerName"), s"${tooFewArgsMsg} ${ruleTriggerActionReqMsg}"),
      (Seq("rule", "create", "ruleName", "triggerName", "actionName", invalidArg), s"${tooManyArgsMsg}${invalidArg}."),
      (Seq("rule", "update"), s"${tooFewArgsMsg} ${ruleTriggerActionReqMsg}"),
      (Seq("rule", "update", "ruleName"), s"${tooFewArgsMsg} ${ruleTriggerActionReqMsg}"),
      (Seq("rule", "update", "ruleName", "triggerName"), s"${tooFewArgsMsg} ${ruleTriggerActionReqMsg}"),
      (Seq("rule", "update", "ruleName", "triggerName", "actionName", invalidArg), s"${tooManyArgsMsg}${invalidArg}."),
      (Seq("rule", "get"), s"${tooFewArgsMsg} ${ruleNameReqMsg}"),
      (Seq("rule", "get", "ruleName", "namespace", invalidArg), s"${tooManyArgsMsg}${invalidArg}."),
      (Seq("rule", "delete"), s"${tooFewArgsMsg} ${ruleNameReqMsg}"),
      (Seq("rule", "delete", "ruleName", invalidArg), s"${tooManyArgsMsg}${invalidArg}."),
      (Seq("rule", "list", "namespace", invalidArg), s"${tooManyArgsMsg}${invalidArg}. ${optNamespaceMsg}"),
      (Seq("rule", "list", invalidArg), entityNameMsg),
      (Seq("trigger", "fire"), s"${tooFewArgsMsg} ${triggerNameReqMsg} ${optPayloadMsg}"),
      (
        Seq("trigger", "fire", "triggerName", "triggerPayload", invalidArg),
        s"${tooManyArgsMsg}${invalidArg}. ${triggerNameReqMsg} ${optPayloadMsg}"),
      (Seq("trigger", "create"), s"${tooFewArgsMsg} ${triggerNameReqMsg}"),
      (Seq("trigger", "create", "triggerName", invalidArg), s"${tooManyArgsMsg}${invalidArg}."),
      (Seq("trigger", "update"), s"${tooFewArgsMsg} ${triggerNameReqMsg}"),
      (Seq("trigger", "update", "triggerName", invalidArg), s"${tooManyArgsMsg}${invalidArg}."),
      (Seq("trigger", "get"), s"${tooFewArgsMsg} ${triggerNameReqMsg}"),
      (Seq("trigger", "get", "triggerName", "namespace", invalidArg), s"${tooManyArgsMsg}${invalidArg}."),
      (Seq("trigger", "delete"), s"${tooFewArgsMsg} ${triggerNameReqMsg}"),
      (Seq("trigger", "delete", "triggerName", invalidArg), s"${tooManyArgsMsg}${invalidArg}."),
      (Seq("trigger", "list", "namespace", invalidArg), s"${tooManyArgsMsg}${invalidArg}. ${optNamespaceMsg}"),
      (Seq("trigger", "list", invalidArg), entityNameMsg))

    invalidArgs foreach {
      case (cmd, err) =>
        withClue(cmd) {
          val rr = wsk.cli(cmd ++ wskprops.overrides, expectedExitCode = ANY_ERROR_EXIT)
          rr.exitCode should (be(ERROR_EXIT) or be(MISUSE_EXIT))
          rr.stderr should include(err)
          rr.stderr should include("Run 'wsk --help' for usage.")
        }
    }
  }

  behavior of "Wsk action parameters"

  it should "create an action with different permutations of limits" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val file = Some(TestUtils.getTestActionFilename("hello.js"))

      def testLimit(timeout: Option[Duration] = None,
                    memory: Option[ByteSize] = None,
                    logs: Option[ByteSize] = None,
                    ec: Int = SUCCESS_EXIT) = {
        // Limits to assert, standard values if CLI omits certain values
        val limits = JsObject(
          "timeout" -> timeout.getOrElse(STD_DURATION).toMillis.toJson,
          "memory" -> memory.getOrElse(STD_MEMORY).toMB.toInt.toJson,
          "logs" -> logs.getOrElse(STD_LOGSIZE).toMB.toInt.toJson)

        val name = "ActionLimitTests" + Instant.now.toEpochMilli
        val createResult = assetHelper.withCleaner(wsk.action, name, confirmDelete = (ec == SUCCESS_EXIT)) {
          (action, _) =>
            val result = action.create(
              name,
              file,
              logsize = logs,
              memory = memory,
              timeout = timeout,
              expectedExitCode = DONTCARE_EXIT)
            withClue(s"create failed for parameters: timeout = $timeout, memory = $memory, logsize = $logs:") {
              result.exitCode should be(ec)
            }
            result
        }

        if (ec == SUCCESS_EXIT) {
          val JsObject(parsedAction) = wsk.action.get(name).stdout.split("\n").tail.mkString.parseJson.asJsObject
          parsedAction("limits") shouldBe limits
        } else {
          createResult.stderr should include("allowed threshold")
        }
      }

      // Assert for valid permutations that the values are set correctly
      for {
        time <- Seq(None, Some(MIN_DURATION), Some(MAX_DURATION))
        mem <- Seq(None, Some(MIN_MEMORY), Some(MAX_MEMORY))
        log <- Seq(None, Some(MIN_LOGSIZE), Some(MAX_LOGSIZE))
      } testLimit(time, mem, log)

      // Assert that invalid permutation are rejected
      testLimit(Some(0.milliseconds), None, None, BAD_REQUEST)
      testLimit(Some(100.minutes), None, None, BAD_REQUEST)
      testLimit(None, Some(0.MB), None, BAD_REQUEST)
      testLimit(None, Some(32768.MB), None, BAD_REQUEST)
      testLimit(None, None, Some(32768.MB), BAD_REQUEST)
  }
}

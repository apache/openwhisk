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

package org.apache.openwhisk.core.cli.test

import akka.http.scaladsl.model.StatusCodes.NotFound
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.StatusCodes.BadRequest
import akka.http.scaladsl.model.StatusCodes.Conflict
import java.time.Instant
import java.time.Clock

import scala.language.postfixOps
import scala.concurrent.duration.DurationInt
import scala.util.Random
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import common.TestHelpers
import common.TestUtils
import common.TestUtils._
import common.WhiskProperties
import common.WskProps
import common.WskTestHelpers
import common.WskActorSystem
import common.rest.WskRestOperations
import spray.json.DefaultJsonProtocol._
import spray.json._
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size.SizeInt
import TestJsonArgs._
import org.apache.openwhisk.http.Messages

/**
 * Tests for basic CLI usage. Some of these tests require a deployed backend.
 */
@RunWith(classOf[JUnitRunner])
class WskRestBasicUsageTests extends TestHelpers with WskTestHelpers with WskActorSystem {

  implicit val wskprops = WskProps()
  val wsk = new WskRestOperations
  val defaultAction: Some[String] = Some(TestUtils.getTestActionFilename("hello.js"))
  val usrAgentHeaderRegEx: String = """\bUser-Agent\b": \[\s+"OpenWhisk\-CLI/1.\d+.*"""

  val requireAPIKeyAnnotation = WhiskProperties.getBooleanProperty("whisk.feature.requireApiKeyAnnotation", true);

  behavior of "Wsk API basic usage"

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

      val run = wsk.action.invoke(fullQualifiedName)
      withActivation(wsk.activation, run) { activation =>
        activation.response.status shouldBe "success"
      }
      val action = wsk.action.get(fullQualifiedName)
      action.getField("name") shouldBe actionName
      action.getField("namespace") shouldBe s"${guestNamespace}/${packageName}"
  }

  behavior of "Wsk actions"

  it should "reject creating entities with invalid names" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val names = Seq(
      ("", NotFound.intValue),
      (" ", BadRequest.intValue),
      ("hi+there", BadRequest.intValue),
      ("$hola", BadRequest.intValue),
      ("dora?", BadRequest.intValue),
      ("|dora|dora?", BadRequest.intValue))

    names foreach {
      case (name, ec) =>
        assetHelper.withCleaner(wsk.action, name, confirmDelete = false) { (action, _) =>
          action.create(name, defaultAction, expectedExitCode = ec)
        }
    }
  }

  it should "create, and get an action to verify parameter and annotation parsing" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "actionAnnotations"
      val file = Some(TestUtils.getTestActionFilename("hello.js"))
      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, file, annotations = getValidJSONTestArgInput, parameters = getValidJSONTestArgInput)
      }

      val action = wsk.action.get(name)
      action.getField("name") shouldBe name

      val receivedParams = wsk.parseJsonString(action.stdout).fields("parameters").convertTo[JsArray].elements
      val receivedAnnots = wsk.parseJsonString(action.stdout).fields("annotations").convertTo[JsArray].elements
      val escapedJSONArr = getValidJSONTestArgOutput.convertTo[JsArray].elements

      for (expectedItem <- escapedJSONArr) {
        receivedParams should contain(expectedItem)
        receivedAnnots should contain(expectedItem)
      }
  }

  it should "delete the given annotations using delAnnotations" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "hello"

    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      val annotations = Map("key1" -> "value1".toJson, "key2" -> "value2".toJson)
      action.create(name, Some(TestUtils.getTestActionFilename("hello.js")), annotations = annotations)
      val annotationString = wsk.parseJsonString(wsk.action.get(name).stdout).fields("annotations").toString

      annotationString should include(""""key":"key1"""")
      annotationString should include(""""value":"value1"""")
      annotationString should include(""""key":"key2"""")
      annotationString should include(""""value":"value2"""")

      //Delete key1 only
      val delAnnotations = Array("key1")

      action.create(
        name,
        Some(TestUtils.getTestActionFilename("hello.js")),
        delAnnotations = delAnnotations,
        update = true)
      val newAnnotationString = wsk.parseJsonString(wsk.action.get(name).stdout).fields("annotations").toString

      newAnnotationString should not include (""""key":"key1"""")
      newAnnotationString should not include (""""value":"value1"""")
      newAnnotationString should include(""""key":"key2"""")
      newAnnotationString should include(""""value":"value2"""")

      action.create(name, Some(TestUtils.getTestActionFilename("hello.js")), update = true)
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

      val action = wsk.action.get(name)
      action.getField("name") shouldBe name

      val receivedParams = wsk.parseJsonString(action.stdout).fields("parameters").convertTo[JsArray].elements
      val receivedAnnots = wsk.parseJsonString(action.stdout).fields("annotations").convertTo[JsArray].elements
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

      val action = wsk.action.get(name)
      action.getField("name") shouldBe name

      val receivedParams = wsk.parseJsonString(action.stdout).fields("parameters").convertTo[JsArray].elements
      val receivedAnnots = wsk.parseJsonString(action.stdout).fields("annotations").convertTo[JsArray].elements
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
        response.status shouldBe ActivationResponse.messageForCode(ActivationResponse.DeveloperError)
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
        response.status shouldBe ActivationResponse.messageForCode(ActivationResponse.DeveloperError)
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
        response.status shouldBe ActivationResponse.messageForCode(ActivationResponse.DeveloperError)
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
        activation.response.result shouldBe Some(JsObject.empty)
        activation.logs shouldBe Some(List.empty)
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
        activation.response.status shouldBe ActivationResponse.messageForCode(ActivationResponse.DeveloperError)
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
      action.create(
        name,
        Some(TestUtils.getTestActionFilename("helloOpenwhiskPackage.js")),
        annotations = Map(Annotations.ProvideApiKeyAnnotationName -> JsTrue))
    }

    val run = wsk.action.invoke(name, Map("ignore_certs" -> true.toJson, "name" -> name.toJson))
    withActivation(wsk.activation, run) { activation =>
      activation.response.status shouldBe "success"
      activation.response.result shouldBe Some(JsObject("delete" -> true.toJson))
      activation.logs.get.mkString(" ") should include("action list has this many actions")
    }

    wsk.action.delete(name, expectedExitCode = NotFound.intValue)
  }

  it should "invoke an action receiving context properties excluding api key" in withAssetCleaner(wskprops) {
    assume(requireAPIKeyAnnotation)
    (wp, assetHelper) =>
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
        fields.get("api_key") shouldBe empty
        fields("namespace") shouldBe namespace
        fields("action_name") shouldBe s"/$namespace/$name"
        fields("action_version") should fullyMatch regex ("""\d+.\d+.\d+""")
        fields("activation_id") shouldBe activation.activationId
        fields("deadline").toLong should be >= start
      }
  }

  it should "invoke an action receiving context properties including api key" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val namespace = wsk.namespace.whois()
      val name = "context"
      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(
          name,
          Some(TestUtils.getTestActionFilename("helloContext.js")),
          annotations = Map(Annotations.ProvideApiKeyAnnotationName -> JsTrue))
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
        fields("action_version") should fullyMatch regex ("""\d+.\d+.\d+""")
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
      //--result takes precedence over --blocking
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
        activation.response.status shouldBe ActivationResponse.messageForCode(ActivationResponse.DeveloperError)
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
    Seq("true", "faLse", "tRue", "nO", "yEs", "no", "raw", "NO", "Raw").foreach { flag =>
      val webEnabled = flag.toLowerCase == "true" || flag.toLowerCase == "yes"
      val rawEnabled = flag.toLowerCase == "raw"

      val runtime = "nodejs:default"
      val name = "webaction-" + flag
      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(
          name,
          Some(TestUtils.getTestActionFilename("echo.js")),
          web = Some(flag.toLowerCase),
          kind = Some(runtime))
      }

      val action = wsk.action.get(name)

      // first check if we got 'nodejs:*' in the exec value
      action
        .getFieldJsValue("annotations")
        .convertTo[Seq[JsObject]]
        .find(_.fields("key").convertTo[String] == "exec")
        .map(_.fields("value"))
        .map(exec => { exec.convertTo[String] should startWith("nodejs:") })
        .getOrElse(fail())

      // then we check the remaining annotations
      val baseAnnotations = Parameters("web-export", JsBoolean(webEnabled || rawEnabled)) ++
        Parameters("raw-http", JsBoolean(rawEnabled)) ++
        Parameters("final", JsBoolean(webEnabled || rawEnabled))
      val testAnnotations = if (requireAPIKeyAnnotation) {
        baseAnnotations ++ Parameters(Annotations.ProvideApiKeyAnnotationName, JsFalse)
      } else baseAnnotations

      // we ignore the exec field here, since we already compared it above
      action
        .getFieldJsValue("annotations")
        .convertTo[Set[JsObject]]
        .filter(annotation => annotation.fields("key").convertTo[String] != "exec") shouldBe testAnnotations.toJsArray
        .convertTo[Set[JsObject]]
    }
  }

  it should "ensure action update creates an action with --web flag" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val runtime = "nodejs:default"
      val name = "webaction"
      val file = Some(TestUtils.getTestActionFilename("echo.js"))

      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, file, web = Some("true"), update = true, kind = Some(runtime))
      }

      val baseAnnotations =
        Parameters("web-export", JsTrue) ++
          Parameters("raw-http", JsFalse) ++
          Parameters("final", JsTrue)

      val testAnnotations = if (requireAPIKeyAnnotation) {
        baseAnnotations ++
          Parameters(Annotations.ProvideApiKeyAnnotationName, JsFalse)
      } else {
        baseAnnotations
      }

      val action = wsk.action.get(name)

      // first check if we got 'nodejs:*' in the exec value
      action
        .getFieldJsValue("annotations")
        .convertTo[Seq[JsObject]]
        .find(_.fields("key").convertTo[String] == "exec")
        .map(_.fields("value"))
        .map(exec => { exec.convertTo[String] should startWith("nodejs:") })
        .getOrElse(fail())

      // then we check the remaining annotations
      // we ignore the exec field here, since we already compared it above
      action
        .getFieldJsValue("annotations")
        .convertTo[Set[JsObject]]
        .filter(annotation => annotation.fields("key").convertTo[String] != "exec") shouldBe testAnnotations.toJsArray
        .convertTo[Set[JsObject]]

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

  behavior of "Wsk packages"

  it should "create, and delete a package" in {
    val name = "createDeletePackage"
    wsk.pkg.create(name).statusCode shouldBe OK
    wsk.pkg.delete(name).statusCode shouldBe OK
  }

  it should "create, and get a package to verify parameter and annotation parsing" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "packageAnnotAndParamParsing"

      assetHelper.withCleaner(wsk.pkg, name) { (pkg, _) =>
        pkg.create(name, annotations = getValidJSONTestArgInput, parameters = getValidJSONTestArgInput)
      }

      val pack = wsk.pkg.get(name)

      val receivedParams = wsk.parseJsonString(pack.stdout).fields("parameters").convertTo[JsArray].elements
      val receivedAnnots = wsk.parseJsonString(pack.stdout).fields("annotations").convertTo[JsArray].elements
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

      val pack = wsk.pkg.get(name)
      val receivedParams = wsk.parseJsonString(pack.stdout).fields("parameters").convertTo[JsArray].elements
      val receivedAnnots = wsk.parseJsonString(pack.stdout).fields("annotations").convertTo[JsArray].elements
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

    wsk.pkg.get(name, expectedExitCode = Conflict.intValue).stderr should include(Messages.conformanceMessage)

    wsk.pkg.bind(name, "bogus", expectedExitCode = Conflict.intValue).stderr should include(
      Messages.requestedBindingIsNotValid)

    wsk.pkg.bind("bogus", "alsobogus", expectedExitCode = BadRequest.intValue).stderr should include(
      Messages.bindingDoesNotExist)

  }

  behavior of "Wsk triggers"

  it should "create, and get a trigger to verify parameter and annotation parsing" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "triggerAnnotAndParamParsing"

      assetHelper.withCleaner(wsk.trigger, name) { (trigger, _) =>
        trigger.create(name, annotations = getValidJSONTestArgInput, parameters = getValidJSONTestArgInput)
      }

      val stdout = wsk.trigger.get(name).stdout

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

      val receivedParams = wsk.parseJsonString(stdout).fields("parameters").convertTo[JsArray].elements
      val receivedAnnots = wsk.parseJsonString(stdout).fields("annotations").convertTo[JsArray].elements
      val escapedJSONArr = getJSONFileOutput.convertTo[JsArray].elements

      for (expectedItem <- escapedJSONArr) {
        receivedParams should contain(expectedItem)
        receivedAnnots should contain(expectedItem)
      }
  }

  it should "create a trigger with the proper parameter and annotation escapes" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "triggerEscapes"

      assetHelper.withCleaner(wsk.trigger, name) { (trigger, _) =>
        trigger.create(name, parameters = getEscapedJSONTestArgInput, annotations = getEscapedJSONTestArgInput)
      }

      val stdout = wsk.trigger.get(name).stdout

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
      trigger.get(name, expectedExitCode = NotFound.intValue)

      trigger.create(name, feed = Some(s"bogus/feed"), expectedExitCode = ANY_ERROR_EXIT).exitCode should equal(
        NOT_FOUND)
      trigger.get(name, expectedExitCode = NotFound.intValue)
    }
  }

  it should "invoke a feed action with the correct lifecyle event when creating, retrieving and deleting a feed trigger" in withAssetCleaner(
    wskprops) { (wp, assetHelper) =>
    val actionName = "echo"
    val triggerName = "feedTest"

    assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
      action.create(actionName, Some(TestUtils.getTestActionFilename("echo.js")))
    }

    try {
      wsk.trigger.create(triggerName, feed = Some(actionName)).statusCode shouldBe OK

      wsk.trigger.get(triggerName).statusCode shouldBe OK
    } finally {
      wsk.trigger.delete(triggerName).statusCode shouldBe OK
    }
  }
}

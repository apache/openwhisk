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

package whisk.core.limits

import akka.http.scaladsl.model.StatusCodes.RequestEntityTooLarge
import akka.http.scaladsl.model.StatusCodes.BadGateway

import java.io.File
import java.io.PrintWriter

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import common.ActivationResult
import common.TestHelpers
import common.TestUtils
import common.WhiskProperties
import common.rest.WskRest
import common.WskProps
import common.WskTestHelpers
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.core.entity.ActivationEntityLimit
import whisk.core.entity.ActivationResponse
import whisk.core.entity.Exec
import whisk.core.entity.size._
import whisk.core.entity.size.SizeString
import whisk.http.Messages

@RunWith(classOf[JUnitRunner])
class ActionLimitsTests extends TestHelpers with WskTestHelpers {

  implicit val wskprops = WskProps()
  val wsk = new WskRest

  val defaultDosAction = TestUtils.getTestActionFilename("timeout.js")
  val allowedActionDuration = 10 seconds

  val testActionsDir = WhiskProperties.getFileRelativeToWhiskHome("tests/dat/actions")
  val actionCodeLimit = Exec.sizeLimit

  val openFileAction = TestUtils.getTestActionFilename("openFiles.js")
  val openFileLimit = 1024
  val minExpectedOpenFiles = openFileLimit - 15 // allow for already opened files in container

  behavior of "Action limits"

  /**
   * Test a long running action that exceeds the maximum execution time allowed for action
   * by setting the action limit explicitly and attempting to run the action for an additional second.
   */
  it should "error with a proper warning if the action exceeds its time limits" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "TestActionCausingTimeout"
      assetHelper.withCleaner(wsk.action, name, confirmDelete = true) { (action, _) =>
        action.create(name, Some(defaultDosAction), timeout = Some(allowedActionDuration))
      }

      val run = wsk.action.invoke(name, Map("payload" -> allowedActionDuration.plus(1 second).toMillis.toJson))
      withActivation(wsk.activation, run) {
        _.response.result.get.fields("error") shouldBe {
          Messages.timedoutActivation(allowedActionDuration, false).toJson
        }
      }
  }

  /**
   * Test an action that does not exceed the allowed execution timeout of an action.
   */
  it should "succeed on an action staying within its time limits" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "TestActionCausingNoTimeout"
    assetHelper.withCleaner(wsk.action, name, confirmDelete = true) { (action, _) =>
      action.create(name, Some(defaultDosAction), timeout = Some(allowedActionDuration))
    }

    val run = wsk.action.invoke(name, Map("payload" -> allowedActionDuration.minus(1 second).toMillis.toJson))
    withActivation(wsk.activation, run) {
      _.response.result.get.toString should include("""[OK] message terminated successfully""")

    }
  }

  it should "succeed but truncate logs, if log size exceeds its limit" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val bytesPerLine = 16
      val allowedSize = 1 megabytes
      val name = "TestActionCausingExceededLogs"
      assetHelper.withCleaner(wsk.action, name, confirmDelete = true) {
        val actionName = TestUtils.getTestActionFilename("dosLogs.js")
        (action, _) =>
          action.create(name, Some(actionName), logsize = Some(allowedSize))
      }

      // Add 10% to allowed size to exceed limit
      val attemptedSize = (allowedSize.toBytes * 1.1).toLong.bytes

      val run = wsk.action.invoke(name, Map("payload" -> attemptedSize.toBytes.toJson))
      withActivation(wsk.activation, run, totalWait = 120 seconds) { response =>
        val lines = response.logs.get
        lines.last shouldBe Messages.truncateLogs(allowedSize)
        (lines.length - 1) shouldBe (allowedSize.toBytes / bytesPerLine)
        // dropping 39 characters (timestamp + stream name)
        // then reform total string adding back newlines
        val actual = lines.dropRight(1).map(_.drop(39)).mkString("", "\n", "\n").sizeInBytes.toBytes
        actual shouldBe allowedSize.toBytes
      }
  }

  Seq(true, false).foreach { blocking =>
    it should s"succeed but truncate result, if result exceeds its limit (blocking: $blocking)" in withAssetCleaner(
      wskprops) { (wp, assetHelper) =>
      val name = "TestActionCausingExcessiveResult"
      assetHelper.withCleaner(wsk.action, name) {
        val actionName = TestUtils.getTestActionFilename("sizedResult.js")
        (action, _) =>
          action.create(name, Some(actionName), timeout = Some(15.seconds))
      }

      val allowedSize = ActivationEntityLimit.MAX_ACTIVATION_ENTITY_LIMIT.toBytes

      def checkResponse(activation: ActivationResult) = {
        val response = activation.response
        response.success shouldBe false
        response.status shouldBe ActivationResponse.messageForCode(ActivationResponse.ContainerError)
        val msg = response.result.get.fields(ActivationResponse.ERROR_FIELD).convertTo[String]
        val expected = Messages.truncatedResponse((allowedSize + 10).B, allowedSize.B)
        withClue(s"is: ${msg.take(expected.length)}\nexpected: $expected") {
          msg.startsWith(expected) shouldBe true
        }
        msg.endsWith("a") shouldBe true
      }

      // this tests an active ack failure to post from invoker
      val args = Map("size" -> (allowedSize + 1).toJson, "char" -> "a".toJson)
      val code = if (blocking) BadGateway.intValue else TestUtils.ACCEPTED
      val rr = wsk.action.invoke(name, args, blocking = blocking, expectedExitCode = code)
      if (blocking) {
        checkResponse(wsk.parseJsonString(rr.respData).convertTo[ActivationResult])
      } else {
        withActivation(wsk.activation, rr, totalWait = 120 seconds) { checkResponse(_) }
      }
    }
  }

  it should "succeed with one log line" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "TestActionWithLogs"
    assetHelper.withCleaner(wsk.action, name, confirmDelete = true) {
      val actionName = TestUtils.getTestActionFilename("dosLogs.js")
      (action, _) =>
        action.create(name, Some(actionName))
    }

    val run = wsk.action.invoke(name)
    withActivation(wsk.activation, run) { response =>
      val logs = response.logs.get
      withClue(logs) { logs.size shouldBe 1 }
      logs.head should include("123456789abcdef")

      response.response.status shouldBe "success"
      response.response.result shouldBe Some(JsObject("msg" -> 1.toJson))
    }
  }

  it should "fail on creating an action with exec which is too big" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "TestActionCausingExecTooBig"

    val actionCode = new File(s"$testActionsDir${File.separator}$name.js")
    actionCode.createNewFile()
    val pw = new PrintWriter(actionCode)
    pw.write("a" * (actionCodeLimit.toBytes + 1).toInt)
    pw.close

    assetHelper.withCleaner(wsk.action, name, confirmDelete = false) { (action, _) =>
      action.create(name, Some(actionCode.getAbsolutePath), expectedExitCode = RequestEntityTooLarge.intValue)
    }

    actionCode.delete
  }

  /**
   * Test an action that does not exceed the allowed number of open files.
   */
  it should "successfully invoke an action when it is within nofile limit" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "TestFileLimitGood-" + System.currentTimeMillis()
      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, Some(openFileAction))
      }

      val run = wsk.action.invoke(name, Map("numFiles" -> minExpectedOpenFiles.toJson))
      withActivation(wsk.activation, run) { activation =>
        activation.response.success shouldBe true
        activation.response.result.get shouldBe {
          JsObject("filesToOpen" -> minExpectedOpenFiles.toJson, "filesOpen" -> minExpectedOpenFiles.toJson)
        }
      }
  }

  /**
   * Test an action that should fail to open way too many files.
   */
  it should "fail to invoke an action when it exceeds nofile limit" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "TestFileLimitBad-" + System.currentTimeMillis()
    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, Some(openFileAction))
    }

    val run = wsk.action.invoke(name, Map("numFiles" -> (openFileLimit + 1).toJson))
    withActivation(wsk.activation, run) { activation =>
      activation.response.success shouldBe false

      val error = activation.response.result.get.fields("error").asJsObject
      error.fields("filesToOpen") shouldBe (openFileLimit + 1).toJson

      error.fields("message") shouldBe {
        JsObject(
          "code" -> "EMFILE".toJson,
          "errno" -> (-24).toJson,
          "path" -> "/dev/zero".toJson,
          "syscall" -> "open".toJson)
      }

      val JsNumber(n) = error.fields("filesOpen")
      n.toInt should be >= minExpectedOpenFiles

      activation.logs
        .getOrElse(List())
        .count(_.contains("ERROR: opened files = ")) shouldBe 1
    }
  }

  it should "be able to run memory intensive actions multiple times by running the GC in the action" in withAssetCleaner(
    wskprops) { (wp, assetHelper) =>
    val name = "TestNodeJsMemoryActionAbleToRunOften"
    assetHelper.withCleaner(wsk.action, name, confirmDelete = true) {
      val allowedMemory = 512 megabytes
      val actionName = TestUtils.getTestActionFilename("memoryWithGC.js")
      (action, _) =>
        action.create(name, Some(actionName), memory = Some(allowedMemory))
    }

    for (a <- 1 to 10) {
      val run = wsk.action.invoke(name, Map("payload" -> "128".toJson))
      withActivation(wsk.activation, run) { response =>
        response.response.status shouldBe "success"
        response.response.result shouldBe Some(JsObject("msg" -> "OK, buffer of size 128 MB has been filled.".toJson))
      }
    }
  }

  it should "be aborted when exceeding its memory limits" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "TestNodeJsMemoryExceeding"
    assetHelper.withCleaner(wsk.action, name, confirmDelete = true) {
      val allowedMemory = 256.megabytes
      val actionName = TestUtils.getTestActionFilename("memoryWithGC.js")
      (action, _) =>
        action.create(name, Some(actionName), memory = Some(allowedMemory))
    }

    val run = wsk.action.invoke(name, Map("payload" -> 512.toJson))
    withActivation(wsk.activation, run) {
      _.response.result.get.fields("error") shouldBe Messages.memoryExhausted.toJson
    }
  }
}

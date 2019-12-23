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

package org.apache.openwhisk.core.limits

import akka.http.scaladsl.model.StatusCodes.PayloadTooLarge
import akka.http.scaladsl.model.StatusCodes.BadGateway
import java.io.File
import java.io.PrintWriter
import java.time.Instant

import scala.concurrent.duration.{Duration, DurationInt}
import scala.language.postfixOps
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import common.ActivationResult
import common.TestHelpers
import common.TestUtils
import common.TestUtils.{BAD_REQUEST, DONTCARE_EXIT, SUCCESS_EXIT}
import common.TimingHelpers
import common.WhiskProperties
import common.rest.WskRestOperations
import common.WskProps
import common.WskTestHelpers
import common.WskActorSystem
import spray.json._
import spray.json.DefaultJsonProtocol._
import org.apache.openwhisk.core.entity.{
  ActivationEntityLimit,
  ActivationResponse,
  ByteSize,
  ConcurrencyLimit,
  Exec,
  LogLimit,
  MemoryLimit,
  TimeLimit
}
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.http.Messages

@RunWith(classOf[JUnitRunner])
class ActionLimitsTests extends TestHelpers with WskTestHelpers with WskActorSystem with TimingHelpers {

  implicit val wskprops = WskProps()
  val wsk = new WskRestOperations

  val defaultSleepAction = TestUtils.getTestActionFilename("sleep.js")
  val allowedActionDuration = 10 seconds

  val testActionsDir = WhiskProperties.getFileRelativeToWhiskHome("tests/dat/actions")
  val actionCodeLimit = Exec.sizeLimit

  val openFileAction = TestUtils.getTestActionFilename("openFiles.js")
  val openFileLimit = 1024
  // Allow for already opened files in container.
  // Attention: do not change this value without a thorough check. It ensures that
  // OpenWhisk users have a minimum number of file descriptors available in their actions.
  // If changes in a managed runtime lead to a decrease of file descriptors available for
  // action code, this may break existing actions.
  // Examples:
  // * With the introduction of Node.js 10, this was changed from "openFileLimit - 15" to
  //   "openFileLimit - 20".
  // * With Docker 18.09.3, we observed test failures and changed to "openFileLimit - 24".
  val minExpectedOpenFiles = openFileLimit - 24

  behavior of "Action limits"

  /**
   * Helper class for the integration test following below.
   * @param timeout the action timeout limit to be set in test
   * @param memory the action memory size limit to be set in test
   * @param logs the action log size limit to be set in test
   * @param concurrency the action concurrency limit to be set in test
   * @param ec the expected exit code when creating the action
   */
  sealed case class PermutationTestParameter(timeout: Option[Duration] = None,
                                             memory: Option[ByteSize] = None,
                                             logs: Option[ByteSize] = None,
                                             concurrency: Option[Int] = None,
                                             ec: Int = SUCCESS_EXIT) {
    override def toString: String =
      s"timeout: ${toTimeoutString}, memory: ${toMemoryString}, logsize: ${toLogsString}, concurrency: ${toConcurrencyString}"

    val toTimeoutString = timeout match {
      case None                                    => "None"
      case Some(TimeLimit.MIN_DURATION)            => s"${TimeLimit.MIN_DURATION} (= min)"
      case Some(TimeLimit.STD_DURATION)            => s"${TimeLimit.STD_DURATION} (= std)"
      case Some(TimeLimit.MAX_DURATION)            => s"${TimeLimit.MAX_DURATION} (= max)"
      case Some(t) if (t < TimeLimit.MIN_DURATION) => s"${t} (< min)"
      case Some(t) if (t > TimeLimit.MAX_DURATION) => s"${t} (> max)"
      case Some(t)                                 => s"${t} (allowed)"
    }

    val toMemoryString = memory match {
      case None                                    => "None"
      case Some(MemoryLimit.MIN_MEMORY)            => s"${MemoryLimit.MIN_MEMORY.toMB.MB} (= min)"
      case Some(MemoryLimit.STD_MEMORY)            => s"${MemoryLimit.STD_MEMORY.toMB.MB} (= std)"
      case Some(MemoryLimit.MAX_MEMORY)            => s"${MemoryLimit.MAX_MEMORY.toMB.MB} (= max)"
      case Some(m) if (m < MemoryLimit.MIN_MEMORY) => s"${m.toMB.MB} (< min)"
      case Some(m) if (m > MemoryLimit.MAX_MEMORY) => s"${m.toMB.MB} (> max)"
      case Some(m)                                 => s"${m.toMB.MB} (allowed)"
    }

    val toLogsString = logs match {
      case None                                  => "None"
      case Some(LogLimit.MIN_LOGSIZE)            => s"${LogLimit.MIN_LOGSIZE} (= min)"
      case Some(LogLimit.STD_LOGSIZE)            => s"${LogLimit.STD_LOGSIZE} (= std)"
      case Some(LogLimit.MAX_LOGSIZE)            => s"${LogLimit.MAX_LOGSIZE} (= max)"
      case Some(l) if (l < LogLimit.MIN_LOGSIZE) => s"${l} (< min)"
      case Some(l) if (l > LogLimit.MAX_LOGSIZE) => s"${l} (> max)"
      case Some(l)                               => s"${l} (allowed)"
    }
    val toConcurrencyString = concurrency match {
      case None                                             => "None"
      case Some(ConcurrencyLimit.MIN_CONCURRENT)            => s"${ConcurrencyLimit.MIN_CONCURRENT} (= min)"
      case Some(ConcurrencyLimit.STD_CONCURRENT)            => s"${ConcurrencyLimit.STD_CONCURRENT} (= std)"
      case Some(ConcurrencyLimit.MAX_CONCURRENT)            => s"${ConcurrencyLimit.MAX_CONCURRENT} (= max)"
      case Some(c) if (c < ConcurrencyLimit.MIN_CONCURRENT) => s"${c} (< min)"
      case Some(c) if (c > ConcurrencyLimit.MAX_CONCURRENT) => s"${c} (> max)"
      case Some(c)                                          => s"${c} (allowed)"
    }
    val toExpectedResultString: String = if (ec == SUCCESS_EXIT) "allow" else "reject"
  }

  val concurrencyEnabled = Option(WhiskProperties.getProperty("whisk.action.concurrency")).exists(_.toBoolean)

  val perms = { // Assert for valid permutations that the values are set correctly
    for {
      time <- Seq(None, Some(TimeLimit.MIN_DURATION), Some(TimeLimit.MAX_DURATION))
      mem <- Seq(None, Some(MemoryLimit.MIN_MEMORY), Some(MemoryLimit.MAX_MEMORY))
      log <- Seq(None, Some(LogLimit.MIN_LOGSIZE), Some(LogLimit.MAX_LOGSIZE))
      concurrency <- if (!concurrencyEnabled || (ConcurrencyLimit.MIN_CONCURRENT == ConcurrencyLimit.MAX_CONCURRENT)) {
        Seq(None, Some(ConcurrencyLimit.MIN_CONCURRENT))
      } else {
        Seq(None, Some(ConcurrencyLimit.MIN_CONCURRENT), Some(ConcurrencyLimit.MAX_CONCURRENT))
      }
    } yield PermutationTestParameter(time, mem, log, concurrency)
  } ++
    // Add variations for negative tests
    Seq(
      PermutationTestParameter(Some(0.milliseconds), None, None, None, BAD_REQUEST), // timeout that is lower than allowed
      PermutationTestParameter(Some(TimeLimit.MAX_DURATION.plus(1 second)), None, None, None, BAD_REQUEST), // timeout that is slightly higher than allowed
      PermutationTestParameter(Some(TimeLimit.MAX_DURATION * 10), None, None, None, BAD_REQUEST), // timeout that is much higher than allowed
      PermutationTestParameter(None, Some(0.MB), None, None, BAD_REQUEST), // memory limit that is lower than allowed
      PermutationTestParameter(None, None, None, Some(0), BAD_REQUEST), // concurrency limit that is lower than allowed
      PermutationTestParameter(None, Some(MemoryLimit.MAX_MEMORY + 1.MB), None, None, BAD_REQUEST), // memory limit that is slightly higher than allowed
      PermutationTestParameter(None, Some((MemoryLimit.MAX_MEMORY.toMB * 5).MB), None, None, BAD_REQUEST), // memory limit that is much higher than allowed
      PermutationTestParameter(None, None, Some((LogLimit.MAX_LOGSIZE.toMB * 5).MB), None, BAD_REQUEST), // log size limit that is much higher than allowed
      PermutationTestParameter(None, None, None, Some(Int.MaxValue), BAD_REQUEST)) // concurrency limit that is much higher than allowed

  /**
   * Integration test to verify that valid timeout, memory, log size, and concurrency limits are accepted
   * when creating an action while any invalid limit is rejected.
   *
   * At the first sight, this test looks like a typical unit test that should not be performed
   * as an integration test. It is performed as an integration test requiring an OpenWhisk
   * deployment to verify that limit settings of the tested deployment fit with the values
   * used in this test.
   */
  perms.foreach { parm =>
    it should s"${parm.toExpectedResultString} creation of an action with these limits: ${parm}" in withAssetCleaner(
      wskprops) { (wp, assetHelper) =>
      val file = Some(TestUtils.getTestActionFilename("hello.js"))

      // Limits to assert, standard values if CLI omits certain values
      val limits = JsObject(
        "timeout" -> parm.timeout.getOrElse(TimeLimit.STD_DURATION).toMillis.toJson,
        "memory" -> parm.memory.getOrElse(MemoryLimit.STD_MEMORY).toMB.toInt.toJson,
        "logs" -> parm.logs.getOrElse(LogLimit.STD_LOGSIZE).toMB.toInt.toJson,
        "concurrency" -> parm.concurrency.getOrElse(ConcurrencyLimit.STD_CONCURRENT).toJson)

      val name = "ActionLimitTests-" + Instant.now.toEpochMilli
      val createResult = assetHelper.withCleaner(wsk.action, name, confirmDelete = (parm.ec == SUCCESS_EXIT)) {
        (action, _) =>
          val result = action.create(
            name,
            file,
            logsize = parm.logs,
            memory = parm.memory,
            timeout = parm.timeout,
            concurrency = parm.concurrency,
            expectedExitCode = DONTCARE_EXIT)
          withClue(s"Unexpected result when creating action '${name}':\n${result.toString}\nFailed assertion:") {
            result.exitCode should be(parm.ec)
          }
          result
      }

      if (parm.ec == SUCCESS_EXIT) {
        val JsObject(parsedAction) = wsk.action.get(name).respBody
        parsedAction("limits") shouldBe limits
      } else {
        createResult.stderr should include("allowed threshold")
      }
    }
  }

  /**
   * Test an action that exceeds its specified time limit. Explicitly specify a rather low time
   * limit to keep the test's runtime short. Invoke an action that sleeps for the specified time
   * limit plus one second.
   */
  it should "error with a proper warning if the action exceeds its time limits" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "TestActionCausingTimeout-" + System.currentTimeMillis()
      assetHelper.withCleaner(wsk.action, name, confirmDelete = true) { (action, _) =>
        action.create(name, Some(defaultSleepAction), timeout = Some(allowedActionDuration))
      }

      val run = wsk.action.invoke(name, Map("sleepTimeInMs" -> allowedActionDuration.plus(1 second).toMillis.toJson))
      withActivation(wsk.activation, run) { result =>
        withClue("Activation result not as expected:") {
          result.response.status shouldBe ActivationResponse.messageForCode(ActivationResponse.DeveloperError)
          result.response.result.get.fields("error") shouldBe {
            Messages.timedoutActivation(allowedActionDuration, init = false).toJson
          }
          result.duration.toInt should be >= allowedActionDuration.toMillis.toInt
        }
      }
  }

  /**
   * Test an action that tightly stays within its specified time limit. Explicitly specify a rather low time
   * limit to keep the test's runtime short. Invoke an action that sleeps for the specified time
   * limit minus one second.
   */
  it should "succeed on an action staying within its time limits" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "TestActionCausingNoTimeout-" + System.currentTimeMillis()
    assetHelper.withCleaner(wsk.action, name, confirmDelete = true) { (action, _) =>
      action.create(name, Some(defaultSleepAction), timeout = Some(allowedActionDuration))
    }

    val run = wsk.action.invoke(name, Map("sleepTimeInMs" -> allowedActionDuration.minus(1 second).toMillis.toJson))
    withActivation(wsk.activation, run) { result =>
      withClue("Activation result not as expected:") {
        result.response.status shouldBe ActivationResponse.messageForCode(ActivationResponse.Success)
        result.response.result.get.toString should include("""Terminated successfully after around""")
      }
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
        lines.last should include(Messages.truncateLogs(allowedSize))
      }
  }

  it should s"successfully invoke an action with a payload close to the limit (${ActivationEntityLimit.MAX_ACTIVATION_ENTITY_LIMIT.toMB} MB)" in withAssetCleaner(
    wskprops) { (wp, assetHelper) =>
    val name = "TestActionCausingJustInBoundaryResult"
    assetHelper.withCleaner(wsk.action, name) {
      val actionName = TestUtils.getTestActionFilename("echo.js")
      (action, _) =>
        action.create(name, Some(actionName), timeout = Some(15.seconds))
    }

    val allowedSize = ActivationEntityLimit.MAX_ACTIVATION_ENTITY_LIMIT.toBytes

    // Needs some bytes grace since activation message is not only the payload.
    val args = Map("p" -> ("a" * (allowedSize - 750).toInt).toJson)
    val start = Instant.now
    val rr = wsk.action.invoke(name, args, blocking = true, expectedExitCode = TestUtils.SUCCESS_EXIT)
    Instant.now.toEpochMilli - start.toEpochMilli should be < 15000L // Ensure activation was not retrieved via DB polling
    val activation = wsk.parseJsonString(rr.respData).convertTo[ActivationResult]

    activation.response.success shouldBe true

    // The payload is echoed and thus the backchannel supports the limit as well.
    activation.response.result shouldBe Some(args.toJson)
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
        response.status shouldBe ActivationResponse.messageForCode(ActivationResponse.ApplicationError)
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
      if (blocking) {
        val start = Instant.now
        val rr = wsk.action.invoke(name, args, blocking = blocking, expectedExitCode = code)
        Instant.now.toEpochMilli - start.toEpochMilli should be < 15000L // Ensure activation was not retrieved via DB polling
        checkResponse(wsk.parseJsonString(rr.respData).convertTo[ActivationResult])
      } else {
        val rr = wsk.action.invoke(name, args, blocking = blocking, expectedExitCode = code)
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
      action.create(name, Some(actionCode.getAbsolutePath), expectedExitCode = PayloadTooLarge.intValue)
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
        .getOrElse(List.empty)
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

  it should "be able to run a memory intensive actions" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "TestNodeJsInvokeHighMemory"
    val allowedMemory = MemoryLimit.MAX_MEMORY
    assetHelper.withCleaner(wsk.action, name, confirmDelete = true) {
      val actionName = TestUtils.getTestActionFilename("memoryWithGC.js")
      (action, _) =>
        action.create(name, Some(actionName), memory = Some(allowedMemory))
    }
    // Don't try to allocate all the memory on invoking the action, as the maximum memory is set for the whole container
    // and not only for the user action.
    val run = wsk.action.invoke(name, Map("payload" -> (allowedMemory.toMB - 56).toJson))
    withActivation(wsk.activation, run) { response =>
      response.response.status shouldBe "success"
    }
  }

  it should "be aborted when exceeding its memory limits" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "TestNodeJsMemoryExceeding"
    assetHelper.withCleaner(wsk.action, name, confirmDelete = true) {
      val allowedMemory = MemoryLimit.MIN_MEMORY
      val actionName = TestUtils.getTestActionFilename("memoryWithGC.js")
      (action, _) =>
        action.create(name, Some(actionName), memory = Some(allowedMemory))
    }

    val payload = MemoryLimit.MIN_MEMORY.toMB * 2
    val run = wsk.action.invoke(name, Map("payload" -> payload.toJson))
    withActivation(wsk.activation, run) {
      _.response.result.get.fields("error") shouldBe Messages.memoryExhausted.toJson
    }
  }

  /**
   * Test that a heavy logging action is interrupted within its timeout limits.
   */
  it should "interrupt the heavy logging action within its time limits" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = s"NodeJsTestLoggingActionCausingTimeout-${System.currentTimeMillis()}"
      assetHelper.withCleaner(wsk.action, name, confirmDelete = true) { (action, _) =>
        action.create(
          name,
          Some(TestUtils.getTestActionFilename("loggingTimeout.js")),
          timeout = Some(allowedActionDuration))
      }
      val duration = allowedActionDuration + 3.minutes
      val checkDuration = allowedActionDuration + 1.minutes
      val run =
        wsk.action.invoke(name, Map("durationMillis" -> duration.toMillis.toJson, "delayMillis" -> 100.toJson))
      withActivation(wsk.activation, run) { result =>
        withClue("Activation result not as expected:") {
          result.response.status shouldBe ActivationResponse.messageForCode(ActivationResponse.DeveloperError)
          result.response.result.get
            .fields("error") shouldBe Messages.timedoutActivation(allowedActionDuration, init = false).toJson
          val logs = result.logs.get
          logs.last should include(Messages.logWarningDeveloperError)

          val parseLogTime = (line: String) => Instant.parse(line.split(' ').head)
          val startTime = parseLogTime(logs.head)
          val endTime = parseLogTime(logs.last)
          between(startTime, endTime).toMillis should be < checkDuration.toMillis
        }
      }
  }
}

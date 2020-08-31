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

package system.basic

import java.time.Instant
import java.util.Date

import io.restassured.RestAssured

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.matching.Regex
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import common._
import common.TestUtils._
import common.rest.WskRestOperations
import org.apache.openwhisk.core.entity.WhiskActivation
import spray.json._
import spray.json.DefaultJsonProtocol._
import system.rest.RestUtil
import org.apache.openwhisk.http.Messages._

/**
 * Tests sequence execution
 */
@RunWith(classOf[JUnitRunner])
class WskSequenceTests extends TestHelpers with WskTestHelpers with StreamLogging with RestUtil with WskActorSystem {

  implicit val wskprops = WskProps()
  val wsk: WskOperations = new WskRestOperations
  val allowedActionDuration = 120 seconds
  val shortDuration = 10 seconds

  behavior of "Wsk Sequence"

  it should "invoke a sequence with normal payload and payload with error field" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "sequence"
      val actions = Seq("split", "sort", "head", "cat")
      for (actionName <- actions) {
        val file = TestUtils.getTestActionFilename(s"$actionName.js")
        assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
          action.create(name = actionName, artifact = Some(file), timeout = Some(allowedActionDuration))
        }
      }

      assetHelper.withCleaner(wsk.action, name) {
        val sequence = actions.mkString(",")
        (action, _) =>
          action.create(name, Some(sequence), kind = Some("sequence"), timeout = Some(allowedActionDuration))
      }

      val now = "it is now " + new Date()
      val args = Array("what time is it?", now)
      val run = wsk.action.invoke(name, Map("payload" -> args.mkString("\n").toJson))
      withActivation(wsk.activation, run, totalWait = 4 * allowedActionDuration) { activation =>
        checkSequenceLogsAndAnnotations(activation, 4) // 4 activations in this sequence
        activation.cause shouldBe None // topmost sequence
        val result = activation.response.result.get
        result.fields.get("payload") shouldBe defined
        result.fields.get("length") should not be defined
        result.fields.get("lines") shouldBe Some(JsArray(Vector(now.toJson)))
      }

      // update action sequence and run it with normal payload
      val newSequence = Seq("split", "sort").mkString(",")
      wsk.action.create(
        name,
        Some(newSequence),
        kind = Some("sequence"),
        timeout = Some(allowedActionDuration),
        update = true)
      val secondrun = wsk.action.invoke(name, Map("payload" -> args.mkString("\n").toJson))
      withActivation(wsk.activation, secondrun, totalWait = 2 * allowedActionDuration) { activation =>
        checkSequenceLogsAndAnnotations(activation, 2) // 2 activations in this sequence
        val result = activation.response.result.get
        result.fields.get("length") shouldBe Some(2.toJson)
        result.fields.get("lines") shouldBe Some(args.sortWith(_.compareTo(_) < 0).toArray.toJson)
      }

      // run sequence with error in the payload
      // sequence should run with no problems, error should be ignored in this test case
      // result of sequence should be identical to previous invocation above
      val payload = Map("error" -> JsString("irrelevant error string"), "payload" -> args.mkString("\n").toJson)
      val thirdrun = wsk.action.invoke(name, payload)
      withActivation(wsk.activation, thirdrun, totalWait = 2 * allowedActionDuration) { activation =>
        checkSequenceLogsAndAnnotations(activation, 2) // 2 activations in this sequence
        val result = activation.response.result.get
        result.fields.get("length") shouldBe Some(2.toJson)
        result.fields.get("lines") shouldBe Some(args.sortWith(_.compareTo(_) < 0).toArray.toJson)
      }
  }

  it should "invoke a sequence with an enclosing sequence action" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val inner_name = "inner_sequence"
    val outer_name = "outer_sequence"
    val inner_actions = Seq("sort", "head")
    val actions = Seq("split") ++ inner_actions ++ Seq("cat")
    // create atomic actions
    for (actionName <- actions) {
      val file = TestUtils.getTestActionFilename(s"$actionName.js")
      assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
        action.create(name = actionName, artifact = Some(file), timeout = Some(allowedActionDuration))
      }
    }

    // create inner sequence
    assetHelper.withCleaner(wsk.action, inner_name) {
      val inner_sequence = inner_actions.mkString(",")
      (action, _) =>
        action.create(inner_name, Some(inner_sequence), kind = Some("sequence"))
    }

    // create outer sequence
    assetHelper.withCleaner(wsk.action, outer_name) {
      val outer_sequence = Seq("split", "inner_sequence", "cat").mkString(",")
      (action, _) =>
        action.create(outer_name, Some(outer_sequence), kind = Some("sequence"))
    }

    val now = "it is now " + new Date()
    val args = Array("what time is it?", now)
    val run = wsk.action.invoke(outer_name, Map("payload" -> args.mkString("\n").toJson))
    withActivation(wsk.activation, run, totalWait = 4 * allowedActionDuration) { activation =>
      checkSequenceLogsAndAnnotations(activation, 3) // 3 activations in this sequence
      activation.cause shouldBe None // topmost sequence
      val result = activation.response.result.get
      result.fields.get("payload") shouldBe defined
      result.fields.get("length") should not be defined
      result.fields.get("lines") shouldBe Some(JsArray(Vector(now.toJson)))
    }
  }

  /**
   * s -> echo, x, echo
   * x -> echo
   *
   * update x -> <limit-1> echo -- should work
   * run s -> should stop after <limit> echo
   *
   * This confirms that a dynamic check on the sequence length holds within the system limit.
   * This is different from creating a long sequence up front which will report a length error at create time.
   */
  it should "replace atomic component in a sequence that is too long and report invoke error" in withAssetCleaner(
    wskprops) { (wp, assetHelper) =>
    val xName = "xSequence"
    val sName = "sSequence"
    val echo = "echo"

    // create echo action
    val file = TestUtils.getTestActionFilename(s"$echo.js")
    assetHelper.withCleaner(wsk.action, echo) { (action, actionName) =>
      action.create(name = actionName, artifact = Some(file), timeout = Some(allowedActionDuration))
    }
    // create x
    assetHelper.withCleaner(wsk.action, xName) { (action, seqName) =>
      action.create(seqName, Some(echo), kind = Some("sequence"))
    }
    // create s
    assetHelper.withCleaner(wsk.action, sName) { (action, seqName) =>
      action.create(seqName, Some(s"$echo,$xName,$echo"), kind = Some("sequence"))
    }

    // invoke s
    val now = "it is now " + new Date()
    val args = Array("what time is it?", now)
    val argsJson = args.mkString("\n").toJson
    val run = wsk.action.invoke(sName, Map("payload" -> argsJson))

    withActivation(wsk.activation, run, totalWait = 2 * allowedActionDuration) { activation =>
      checkSequenceLogsAndAnnotations(activation, 3) // 3 activations in this sequence
      val result = activation.response.result.get
      result.fields.get("payload") shouldBe Some(argsJson)
    }
    // update x with limit echo
    val limit: Int = {
      val response = RestAssured.given.config(sslconfig).get(getServiceURL)
      response.statusCode should be(200)
      response.body.asString.parseJson.asJsObject.fields("limits").asJsObject.fields("sequence_length").convertTo[Int]
    }
    val manyEcho = for (i <- 1 to limit) yield echo

    wsk.action.create(xName, Some(manyEcho.mkString(",")), kind = Some("sequence"), update = true)

    val updateRun = wsk.action.invoke(sName, Map("payload" -> argsJson))
    withActivation(wsk.activation, updateRun, totalWait = 2 * allowedActionDuration) { activation =>
      activation.response.status shouldBe ("application error")
      checkSequenceLogsAndAnnotations(activation, 2)
      val result = activation.response.result.get
      result.fields.get("error") shouldBe Some(JsString(sequenceIsTooLong))
      // check that inner sequence had only (limit - 1) activations
      val innerSeq = activation.logs.get(1) // the id of the inner sequence activation
      val getInnerSeq = wsk.activation.get(Some(innerSeq))
      withActivation(wsk.activation, getInnerSeq, totalWait = allowedActionDuration) { innerSeqActivation =>
        innerSeqActivation.logs.get.size shouldBe (limit - 1)
        innerSeqActivation.cause shouldBe Some(activation.activationId)
      }
    }
  }

  it should "create and run a sequence in a package with parameters" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val sName = "sSequence"

      // create a package
      val pkgName = "echopackage"
      val pkgStr = "LonelyPackage"
      assetHelper.withCleaner(wsk.pkg, pkgName) { (pkg, name) =>
        pkg.create(name, Map("payload" -> JsString(pkgStr)))
      }
      val helloName = "hello"
      val helloWithPkg = s"$pkgName/$helloName"

      // create hello action in package
      val file = TestUtils.getTestActionFilename(s"$helloName.js")
      val actionStr = "AtomicAction"
      assetHelper.withCleaner(wsk.action, helloWithPkg) { (action, actionName) =>
        action.create(
          name = actionName,
          artifact = Some(file),
          timeout = Some(allowedActionDuration),
          parameters = Map("payload" -> JsString(actionStr)))
      }
      // create s
      assetHelper.withCleaner(wsk.action, sName) { (action, seqName) =>
        action.create(seqName, Some(helloWithPkg), kind = Some("sequence"))
      }
      val run = wsk.action.invoke(sName)
      // action params trump package params
      checkLogsAtomicAction(0, run, new Regex(actionStr))
      // run with some parameters
      val sequenceStr = "AlmightySequence"
      val sequenceParamRun = wsk.action.invoke(sName, parameters = Map("payload" -> JsString(sequenceStr)))
      // sequence param should be passed to the first atomic action and trump the action params
      checkLogsAtomicAction(0, sequenceParamRun, new Regex(sequenceStr))
      // update action and remove the params by sending an unused param that overrides previous params
      wsk.action.create(
        name = helloWithPkg,
        artifact = Some(file),
        timeout = Some(allowedActionDuration),
        parameters = Map("param" -> JsString("irrelevant")),
        update = true)
      val sequenceParamSecondRun = wsk.action.invoke(sName, parameters = Map("payload" -> JsString(sequenceStr)))
      // sequence param should be passed to the first atomic action and trump the package params
      checkLogsAtomicAction(0, sequenceParamSecondRun, new Regex(sequenceStr))
      val pkgParamRun = wsk.action.invoke(sName)
      // no sequence params, no atomic action params used, the pkg params should show up
      checkLogsAtomicAction(0, pkgParamRun, new Regex(pkgStr))
  }

  it should "run a sequence with an action in a package binding with parameters" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val packageName = "package1"
      val bindName = "package2"
      val actionName = "print"
      val packageActionName = packageName + "/" + actionName
      val bindActionName = bindName + "/" + actionName
      val packageParams = Map("key1a" -> "value1a".toJson, "key1b" -> "value1b".toJson)
      val bindParams = Map("key2a" -> "value2a".toJson, "key1b" -> "value2b".toJson)
      val actionParams = Map("key0" -> "value0".toJson)
      val file = TestUtils.getTestActionFilename("printParams.js")
      assetHelper.withCleaner(wsk.pkg, packageName) { (pkg, _) =>
        pkg.create(packageName, packageParams)
      }
      assetHelper.withCleaner(wsk.action, packageActionName) { (action, _) =>
        action.create(packageActionName, Some(file), parameters = actionParams)
      }
      assetHelper.withCleaner(wsk.pkg, bindName) { (pkg, _) =>
        pkg.bind(packageName, bindName, bindParams)
      }
      // sequence
      val sName = "sequenceWithBindingParams"
      assetHelper.withCleaner(wsk.action, sName) { (action, seqName) =>
        action.create(seqName, Some(bindActionName), kind = Some("sequence"))
      }
      // Check that inherited parameters are passed to the action.
      val now = new Date().toString()
      val run = wsk.action.invoke(sName, Map("payload" -> now.toJson))
      // action params trump package params
      checkLogsAtomicAction(
        0,
        run,
        new Regex(String.format(".*key0: value0.*key1a: value1a.*key1b: value2b.*key2a: value2a.*payload: %s", now)))
  }

  it should "contain an binding annotation if invoked action is in a package binding" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val ns = wsk.namespace.whois()
      val packageName = "package1"
      val bindName = "package2"
      val actionName = "print"
      val packageActionName = packageName + "/" + actionName
      val bindActionName = bindName + "/" + actionName

      val file = TestUtils.getTestActionFilename("echo.js")
      assetHelper.withCleaner(wsk.pkg, packageName) { (pkg, _) =>
        pkg.create(packageName)
      }
      assetHelper.withCleaner(wsk.action, packageActionName) { (action, _) =>
        action.create(packageActionName, Some(file))
      }
      assetHelper.withCleaner(wsk.pkg, bindName) { (pkg, _) =>
        pkg.bind(packageName, bindName)
      }
      // sequence
      val sequenceActionName = "sequenceWithBinding"
      val sName = packageName + "/" + sequenceActionName
      val bName = bindName + "/" + sequenceActionName
      assetHelper.withCleaner(wsk.action, sName) { (action, seqName) =>
        action.create(seqName, Some(bindActionName), kind = Some("sequence"))
      }

      val run = wsk.action.invoke(bName)
      withActivation(wsk.activation, run, totalWait = 2 * allowedActionDuration) { activation =>
        val binding = activation.getAnnotationValue(WhiskActivation.bindingAnnotation)
        binding shouldBe defined
        binding.get shouldBe JsString(ns + "/" + bindName)

        for (id <- activation.logs.get) {
          withActivation(
            wsk.activation,
            id,
            initialWait = 1 second,
            pollPeriod = 60 seconds,
            totalWait = allowedActionDuration) { componentActivation =>
            val binding = componentActivation.getAnnotationValue(WhiskActivation.bindingAnnotation)
            binding shouldBe defined
            binding.get shouldBe JsString(ns + "/" + bindName)
          }
        }
      }
  }

  /**
   * s -> apperror, echo
   * only apperror should run
   */
  it should "stop execution of a sequence (with no payload) on error" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val sName = "sSequence"
      val apperror = "applicationError"
      val echo = "echo"

      // create actions
      val actions = Seq(apperror, echo)
      for (actionName <- actions) {
        val file = TestUtils.getTestActionFilename(s"$actionName.js")
        assetHelper.withCleaner(wsk.action, actionName) { (action, actionName) =>
          action.create(name = actionName, artifact = Some(file), timeout = Some(allowedActionDuration))
        }
      }
      // create sequence s
      assetHelper.withCleaner(wsk.action, sName) { (action, seqName) =>
        action.create(seqName, artifact = Some(actions.mkString(",")), kind = Some("sequence"))
      }
      // run sequence s with no payload
      val run = wsk.action.invoke(sName)
      withActivation(wsk.activation, run, totalWait = 2 * allowedActionDuration) { activation =>
        checkSequenceLogsAndAnnotations(activation, 1) // only the first action should have run
        activation.response.success shouldBe (false)
        // the status should be error
        activation.response.status shouldBe ("application error")
        val result = activation.response.result.get
        // the result of the activation should be the application error
        result shouldBe (JsObject("error" -> JsString("This error thrown on purpose by the action.")))
      }
  }

  /**
   * s -> echo, initforever
   * should run both, but error
   */
  it should "propagate execution error (timeout) from atomic action to sequence" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val sName = "sSequence"
      val initforever = "initforever"
      val echo = "echo"

      // create actions
      val actions = Seq(echo, initforever)
      // timeouts for the action; make the one for initforever short
      val timeout = Map(echo -> allowedActionDuration, initforever -> shortDuration)
      for (actionName <- actions) {
        val file = TestUtils.getTestActionFilename(s"$actionName.js")
        assetHelper.withCleaner(wsk.action, actionName) { (action, actionName) =>
          action.create(name = actionName, artifact = Some(file), timeout = Some(timeout(actionName)))
        }
      }
      // create sequence s
      assetHelper.withCleaner(wsk.action, sName) { (action, seqName) =>
        action.create(seqName, artifact = Some(actions.mkString(",")), kind = Some("sequence"))
      }
      // run sequence s with no payload
      val run = wsk.action.invoke(sName)
      withActivation(wsk.activation, run, totalWait = 2 * allowedActionDuration) { activation =>
        checkSequenceLogsAndAnnotations(activation, 2) // 2 actions
        activation.response.success shouldBe (false)
        // the status should be error
        //activation.response.status shouldBe("application error")
        val result = activation.response.result.get
        // the result of the activation should be timeout or an abnormal initialization
        result should (be(JsObject("error" -> timedoutActivation(shortDuration, true).toJson)) or be(
          JsObject("error" -> abnormalInitialization.toJson)))
      }
  }

  /**
   * s -> echo, sleep
   * sleep sleeps for 90s, timeout set at 120s
   * should run both, the blocking call should be transformed into a non-blocking call, but finish executing
   */
  it should "execute a sequence in blocking fashion and finish execution even if longer than blocking response timeout" in withAssetCleaner(
    wskprops) { (wp, assetHelper) =>
    val sName = "sSequence"
    val sleep = "sleep"
    val echo = "echo"

    // create actions
    val actions = Seq(echo, sleep)
    for (actionName <- actions) {
      val file = TestUtils.getTestActionFilename(s"$actionName.js")
      assetHelper.withCleaner(wsk.action, actionName) { (action, actionName) =>
        action.create(name = actionName, artifact = Some(file), timeout = Some(allowedActionDuration))
      }
    }
    // create sequence s
    assetHelper.withCleaner(wsk.action, sName) { (action, seqName) =>
      action.create(seqName, artifact = Some(actions.mkString(",")), kind = Some("sequence"))
    }
    // run sequence s with sleep time
    val sleepTime = 90 seconds
    val run = wsk.action.invoke(
      sName,
      parameters = Map("sleepTimeInMs" -> sleepTime.toMillis.toJson),
      blocking = true,
      expectedExitCode = ACCEPTED)
    withActivation(wsk.activation, run, initialWait = 5 seconds, totalWait = 3 * allowedActionDuration) { activation =>
      checkSequenceLogsAndAnnotations(activation, 2) // 2 actions
      activation.response.success shouldBe (true)
      val result = activation.response.result.get
      result.toString should include("""Terminated successfully after around""")
    }
  }

  /**
   * sequence s -> echo
   * t trigger with payload
   * rule r: t -> s
   */
  it should "execute a sequence that is part of a rule and pass the trigger parameters to the sequence" in withAssetCleaner(
    wskprops) { (wp, assetHelper) =>
    val seqName = "seqRule"
    val actionName = "echo"
    val triggerName = "trigSeq"
    val ruleName = "ruleSeq"

    val itIsNow = "it is now " + new Date()
    // set up all entities
    // trigger
    val triggerPayload: Map[String, JsValue] = Map("payload" -> JsString(itIsNow))
    assetHelper.withCleaner(wsk.trigger, triggerName) { (trigger, name) =>
      trigger.create(name, parameters = triggerPayload)
    }
    // action
    val file = TestUtils.getTestActionFilename(s"$actionName.js")
    assetHelper.withCleaner(wsk.action, actionName) { (action, actionName) =>
      action.create(name = actionName, artifact = Some(file), timeout = Some(allowedActionDuration))
    }
    // sequence
    assetHelper.withCleaner(wsk.action, seqName) { (action, seqName) =>
      action.create(seqName, artifact = Some(actionName), kind = Some("sequence"))
    }
    // rule
    assetHelper.withCleaner(wsk.rule, ruleName) { (rule, name) =>
      rule.create(name, triggerName, seqName)
    }
    // fire trigger
    val run = wsk.trigger.fire(triggerName)
    // check that the sequence was invoked and that the echo action produced the expected result
    checkEchoSeqRuleResult(run, seqName, JsObject(triggerPayload))
    // fire trigger with new payload
    val now = "this is now: " + Instant.now
    val newPayload = Map("payload" -> JsString(now))
    val newRun = wsk.trigger.fire(triggerName, newPayload)
    checkEchoSeqRuleResult(newRun, seqName, JsObject(newPayload))
  }

  it should "run a sub-action even if it is updated while the sequence action is running" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val seqName = "sequence"
      val sleep = "sleep"
      val echo = "echo"
      val slowInvokeDuration = 5.seconds

      // create echo action
      val echoFile = TestUtils.getTestActionFilename(s"$echo.js")
      assetHelper.withCleaner(wsk.action, echo) { (action, actionName) =>
        action.create(name = actionName, artifact = Some(echoFile), timeout = Some(allowedActionDuration))
      }
      // create sleep action
      val sleepFile = TestUtils.getTestActionFilename(s"$sleep.js")
      assetHelper.withCleaner(wsk.action, sleep) { (action, actionName) =>
        action.create(
          name = sleep,
          artifact = Some(sleepFile),
          parameters = Map("sleepTimeInMs" -> slowInvokeDuration.toMillis.toJson),
          timeout = Some(allowedActionDuration))
      }

      // create sequence
      assetHelper.withCleaner(wsk.action, seqName) { (action, seqName) =>
        action.create(seqName, Some(s"$sleep,$echo"), kind = Some("sequence"))
      }
      val run = wsk.action.invoke(seqName)

      // update the sub-action before the sequence action invokes it
      wsk.action.create(name = echo, artifact = None, annotations = Map("a" -> JsString("A")), update = true)
      wsk.action.invoke(echo)

      wsk.action.create(name = echo, artifact = None, annotations = Map("b" -> JsString("B")), update = true)
      wsk.action.invoke(echo)

      withActivation(wsk.activation, run, totalWait = 2 * allowedActionDuration) { activation =>
        activation.response.status shouldBe "success"
      }
  }

  /**
   * checks the result of an echo sequence connected to a trigger through a rule
   * @param triggerFireRun the run result of firing the trigger
   * @param seqName the sequence name
   * @param triggerPayload the payload used for the trigger (that should be reflected in the sequence result)
   */
  private def checkEchoSeqRuleResult(triggerFireRun: RunResult, seqName: String, triggerPayload: JsObject) = {
    withActivation(wsk.activation, triggerFireRun) { triggerActivation =>
      val ruleActivation = triggerActivation.logs.get.map(_.parseJson.convertTo[RuleActivationResult]).head
      withActivation(wsk.activation, ruleActivation.activationId) { actionActivation =>
        actionActivation.response.result shouldBe Some(triggerPayload)
        actionActivation.cause shouldBe None
      }
    }
  }

  /**
   * checks logs for the activation of a sequence (length/size and ids)
   * checks that the cause field for composing atomic actions is set properly
   * checks duration
   * checks memory
   */
  private def checkSequenceLogsAndAnnotations(activation: ActivationResult, size: Int) = {
    activation.logs shouldBe defined
    // check that the logs are what they are supposed to be (activation ids)
    // check that the cause field is properly set for these activations
    activation.logs.get.size shouldBe (size) // the number of activations in this sequence
    var totalTime: Long = 0
    var maxMemory: Long = 0
    for (id <- activation.logs.get) {
      withActivation(
        wsk.activation,
        id,
        initialWait = 1 second,
        pollPeriod = 60 seconds,
        totalWait = allowedActionDuration) { componentActivation =>
        componentActivation.cause shouldBe defined
        componentActivation.cause.get shouldBe (activation.activationId)
        // check waitTime
        val waitTime = componentActivation.getAnnotationValue("waitTime")
        waitTime shouldBe defined
        // check causedBy
        val causedBy = componentActivation.getAnnotationValue("causedBy")
        causedBy shouldBe defined
        causedBy.get shouldBe (JsString("sequence"))
        totalTime += componentActivation.duration
        // extract memory
        val mem = extractMemoryAnnotation(componentActivation)
        maxMemory = maxMemory max mem
      }
    }
    // extract duration
    activation.duration shouldBe (totalTime)
    // extract memory
    activation.annotations shouldBe defined
    val memory = extractMemoryAnnotation(activation)
    memory shouldBe (maxMemory)
  }

  /** checks that the logs of the idx-th atomic action from a sequence contains logsStr */
  private def checkLogsAtomicAction(atomicActionIdx: Int, run: RunResult, regex: Regex): Unit = {
    withActivation(wsk.activation, run, totalWait = 2 * allowedActionDuration) { activation =>
      checkSequenceLogsAndAnnotations(activation, 1)
      val componentId = activation.logs.get(atomicActionIdx)
      val getComponentActivation = wsk.activation.get(Some(componentId))
      withActivation(wsk.activation, getComponentActivation, totalWait = allowedActionDuration) { componentActivation =>
        withClue(componentActivation) {
          componentActivation.logs shouldBe defined
          val logs = componentActivation.logs.get.mkString(" ")
          regex.findFirstIn(logs) shouldBe defined
        }
      }
    }
  }

  private def extractMemoryAnnotation(activation: ActivationResult): Long = {
    val limits = activation.getAnnotationValue("limits")
    limits shouldBe defined
    limits.get.asJsObject.getFields("memory")(0).convertTo[Long]
  }
}

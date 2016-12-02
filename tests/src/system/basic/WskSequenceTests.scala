/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package system.basic

import java.util.Date

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import common.JsHelpers
import common.TestHelpers
import common.TestUtils
import common.TestUtils._
import common.Wsk
import common.WskProps
import common.WskTestHelpers
import spray.json.DefaultJsonProtocol._
import spray.json._
import spray.testkit.ScalatestRouteTest
import whisk.core.WhiskConfig
import whisk.http.Messages.sequenceIsTooLong

import scala.util.matching.Regex

/**
 * Tests sequence execution
 */

@RunWith(classOf[JUnitRunner])
class WskSequenceTests
    extends TestHelpers
    with JsHelpers
    with ScalatestRouteTest
    with WskTestHelpers {

    implicit val wskprops = WskProps()
    val wsk = new Wsk
    val allowedActionDuration = 120 seconds
    val shortDuration = 10 seconds
    val defaultNamespace = wskprops.namespace

    val whiskConfig = new WhiskConfig(Map(WhiskConfig.actionSequenceDefaultLimit -> null))
    assert(whiskConfig.isValid)

    behavior of "Wsk Sequence"

    it should "invoke a blocking sequence action and invoke the updated sequence with normal payload and payload with error field" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "sequence"
            val actions = Seq("split", "sort", "head", "cat")
            for (actionName <- actions) {
                val file = TestUtils.getTestActionFilename(s"$actionName.js")
                assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
                    action.create(name = actionName, artifact = Some(file), timeout = Some(allowedActionDuration))
                }
            }

            println(s"Sequence $actions")
            assetHelper.withCleaner(wsk.action, name) {
                val sequence = actions.mkString(",")
                (action, _) => action.create(name, Some(sequence), kind = Some("sequence"), timeout = Some(allowedActionDuration))
            }

            val now = "it is now " + new Date()
            val args = Array("what time is it?", now)
            val run = wsk.action.invoke(name, Map("payload" -> args.mkString("\n").toJson))
            withActivation(wsk.activation, run, totalWait = 4 * allowedActionDuration) {
                activation =>
                    checkSequenceLogsAndAnnotations(activation, 4) // 4 activations in this sequence
                    activation.cause shouldBe None // topmost sequence
                    val result = activation.response.result.get
                    result.fields.get("payload") shouldBe defined
                    result.fields.get("length") should not be defined
                    result.fields.get("lines") shouldBe Some(JsArray(Vector(now.toJson)))
            }

            // update action sequence and run it with normal payload
            val newSequence = Seq("split", "sort").mkString(",")
            println(s"Update sequence to $newSequence")
            wsk.action.create(name, Some(newSequence), kind = Some("sequence"), timeout = Some(allowedActionDuration), update = true)
            val secondrun = wsk.action.invoke(name, Map("payload" -> args.mkString("\n").toJson))
            withActivation(wsk.activation, secondrun, totalWait = 2 * allowedActionDuration) {
                activation =>
                    checkSequenceLogsAndAnnotations(activation, 2) // 2 activations in this sequence
                    val result = activation.response.result.get
                    result.fields.get("length") shouldBe Some(2.toJson)
                    result.fields.get("lines") shouldBe Some(args.sortWith(_.compareTo(_) < 0).toArray.toJson)
            }

            println("Run sequence with error in payload")
            // run sequence with error in the payload; nothing should run
            val payload = Map("error" -> JsString("irrelevant error string"))
            val thirdrun = wsk.action.invoke(name, payload)
            withActivation(wsk.activation, thirdrun, totalWait = allowedActionDuration) {
                activation =>
                    activation.logs shouldBe defined
                    // no activations should have run
                    activation.logs.get.size shouldBe (0)
                    activation.response.success shouldBe (false)
                    // the status should be error
                    activation.response.status shouldBe ("application error")
                    val result = activation.response.result.get
                    // the result of the activation should be the payload
                    result shouldBe (JsObject(payload))

            }
    }

    /**
     * s -> echo, x, echo
     * x -> echo
     *
     * update x -> <limit-1> echo -- should work
     * run s -> should stop after <limit> echo
     */
    it should "create a sequence, run it, update one of the atomic actions to a sequence and stop executing the outer sequence when limit reached" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val xName = "xSequence"
            val sName = "sSequence"
            val echo = "echo"

            // create echo action
            val file = TestUtils.getTestActionFilename(s"$echo.js")
            assetHelper.withCleaner(wsk.action, echo) { (action, actionName) =>
                action.create(name = actionName, artifact = Some(file), timeout = Some(allowedActionDuration))
            }
            // create x
            assetHelper.withCleaner(wsk.action, xName) {
                (action, seqName) => action.create(seqName, Some(echo), kind = Some("sequence"))
            }
            // create s
            assetHelper.withCleaner(wsk.action, sName) {
                (action, seqName) => action.create(seqName, Some(s"$echo,$xName,$echo"), kind = Some("sequence"))
            }

            // invoke s
            val now = "it is now " + new Date()
            val args = Array("what time is it?", now)
            val argsJson = args.mkString("\n").toJson
            val run = wsk.action.invoke(sName, Map("payload" -> argsJson))
            println(s"RUN: ${run.stdout}")
            withActivation(wsk.activation, run, totalWait = 2 * allowedActionDuration) {
                activation =>
                    checkSequenceLogsAndAnnotations(activation, 3) // 3 activations in this sequence
                    val result = activation.response.result.get
                    result.fields.get("payload") shouldBe Some(argsJson)
            }
            // update x with limit echo
            val limit = whiskConfig.actionSequenceLimit.toInt
            val manyEcho = for (i <- 1 to limit) yield echo

            wsk.action.create(xName, Some(manyEcho.mkString(",")), kind = Some("sequence"), update = true)

            val updateRun = wsk.action.invoke(sName, Map("payload" -> argsJson))
            withActivation(wsk.activation, updateRun, totalWait = 2 * allowedActionDuration) {
                activation =>
                    activation.response.status shouldBe ("application error")
                    checkSequenceLogsAndAnnotations(activation, 2)
                    val result = activation.response.result.get
                    result.fields.get("error") shouldBe Some(JsString(sequenceIsTooLong))
                    // check that inner sequence had only (limit - 1) activations
                    val innerSeq = activation.logs.get(1) // the id of the inner sequence activation
                    val getInnerSeq = wsk.activation.get(innerSeq)
                    withActivation(wsk.activation, getInnerSeq, totalWait = allowedActionDuration) {
                        innerSeqActivation =>
                            innerSeqActivation.logs.get.size shouldBe (limit - 1)
                            innerSeqActivation.cause shouldBe defined
                            innerSeqActivation.cause.get shouldBe (activation.activationId)
                    }
            }
    }

    it should "invoke a blocking sequence action with an enclosing sequence action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
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
                (action, _) => action.create(inner_name, Some(inner_sequence), kind = Some("sequence"))
            }

            // create outer sequence
            assetHelper.withCleaner(wsk.action, outer_name) {
                val outer_sequence = Seq("split", "inner_sequence", "cat").mkString(",")
                (action, _) => action.create(outer_name, Some(outer_sequence), kind = Some("sequence"))
            }

            val now = "it is now " + new Date()
            val args = Array("what time is it?", now)
            val run = wsk.action.invoke(outer_name, Map("payload" -> args.mkString("\n").toJson))
            withActivation(wsk.activation, run, totalWait = 4 * allowedActionDuration) {
                activation =>
                    checkSequenceLogsAndAnnotations(activation, 3) // 3 activations in this sequence
                    activation.cause shouldBe None // topmost sequence
                    val result = activation.response.result.get
                    result.fields.get("payload") shouldBe defined
                    result.fields.get("length") should not be defined
                    result.fields.get("lines") shouldBe Some(JsArray(Vector(now.toJson)))
            }
    }

    it should "create and run a sequence in a package with parameters" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val sName = "sSequence"

            // create a package
            val pkgName = "echopackage"
            val pkgStr = "LonelyPackage"
            assetHelper.withCleaner(wsk.pkg, pkgName) {
                (pkg, name) => pkg.create(name, Map("payload" -> JsString(pkgStr)))
            }
            val helloName = "hello"
            val helloWithPkg = s"$pkgName/$helloName"

            // create hello action in package
            val file = TestUtils.getTestActionFilename(s"$helloName.js")
            val actionStr = "AtomicAction"
            assetHelper.withCleaner(wsk.action, helloWithPkg) { (action, actionName) =>
                action.create(name = actionName, artifact = Some(file), timeout = Some(allowedActionDuration), parameters = Map("payload" -> JsString(actionStr)))
            }
            // create s
            assetHelper.withCleaner(wsk.action, sName) {
                (action, seqName) => action.create(seqName, Some(helloWithPkg), kind = Some("sequence"))
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
            wsk.action.create(name = helloWithPkg, artifact = Some(file), timeout = Some(allowedActionDuration), parameters = Map("param" -> JsString("irrelevant")), update = true)
            val sequenceParamSecondRun = wsk.action.invoke(sName, parameters = Map("payload" -> JsString(sequenceStr)))
            // sequence param should be passed to the first atomic action and trump the package params
            checkLogsAtomicAction(0, sequenceParamSecondRun, new Regex(sequenceStr))
            val pkgParamRun = wsk.action.invoke(sName)
            // no sequence params, no atomic action params used, the pkg params should show up
            checkLogsAtomicAction(0, pkgParamRun, new Regex(pkgStr))
    }

    it should "run a sequence with an action in a package binding with parameters" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val packageName = s"/$defaultNamespace/package1"
            val bindName = s"/$defaultNamespace/package2"
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
            assetHelper.withCleaner(wsk.action, sName) {
                (action, seqName) => action.create(seqName, Some(bindActionName), kind = Some("sequence"))
            }
            // Check that inherited parameters are passed to the action.
            val now = new Date().toString()
            val run = wsk.action.invoke(sName, Map("payload" -> now.toJson))
            // action params trump package params
            checkLogsAtomicAction(0, run, new Regex(String.format(".*key0: value0.*key1a: value1a.*key1b: value2b.*key2a: value2a.*payload: %s", now)))
    }
    /**
     * s -> apperror, echo
     * only apperror should run
     */
    it should "stop execution of a sequence (with no payload) on error" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val sName = "sSequence"
            val apperror = "applicationError2"
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
            assetHelper.withCleaner(wsk.action, sName) {
                (action, seqName) => action.create(seqName, artifact = Some(actions.mkString(",")), kind = Some("sequence"))
            }
            // run sequence s with no payload
            val run = wsk.action.invoke(sName)
            withActivation(wsk.activation, run, totalWait = 2 * allowedActionDuration) {
                activation =>
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
            assetHelper.withCleaner(wsk.action, sName) {
                (action, seqName) => action.create(seqName, artifact = Some(actions.mkString(",")), kind = Some("sequence"))
            }
            // run sequence s with no payload
            val run = wsk.action.invoke(sName)
            withActivation(wsk.activation, run, totalWait = 2 * allowedActionDuration) {
                activation =>
                    checkSequenceLogsAndAnnotations(activation, 2) // 2 actions
                    activation.response.success shouldBe (false)
                    // the status should be error
                    //activation.response.status shouldBe("application error")
                    val result = activation.response.result.get
                    // the result of the activation should be timeout
                    result shouldBe (JsObject("error" -> JsString("The action exceeded its time limits of 10000 milliseconds during initialization.")))
            }
    }

    /**
     * s -> echo, sleep
     * sleep sleeps for 90s, timeout set at 120s
     * should run both, the blocking call should be transformed into a non-blocking call, but finish executing
     */
    it should "execute a sequence in blocking fashion and finish execution even if longer than blocking response timeout" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val sName = "sSequence"
            val sleep = "timeout"
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
            assetHelper.withCleaner(wsk.action, sName) {
                (action, seqName) => action.create(seqName, artifact = Some(actions.mkString(",")), kind = Some("sequence"))
            }
            // run sequence s with sleep equal to payload
            val payload = 65000
            val run = wsk.action.invoke(sName, parameters = Map("payload" -> JsNumber(payload)), blocking = true)
            withActivation(wsk.activation, run, initialWait = 5 seconds, totalWait = 3 * allowedActionDuration) {
                activation =>
                    checkSequenceLogsAndAnnotations(activation, 2) // 2 actions
                    activation.response.success shouldBe (true)
                    // the status should be error
                    //activation.response.status shouldBe("application error")
                    val result = activation.response.result.get
                    // the result of the activation should be timeout
                    result shouldBe (JsObject("msg" -> JsString(s"[OK] message terminated successfully after $payload milliseconds.")))
            }
    }

    /**
     * checks logs for the activation of a sequence (length/size and ids)
     * checks that the cause field for composing atomic actions is set properly
     * checks duration
     * checks memory
     */
    private def checkSequenceLogsAndAnnotations(activation: CliActivation, size: Int) = {
        activation.logs shouldBe defined
        // check that the logs are what they are supposed to be (activation ids)
        // check that the cause field is properly set for these activations
        activation.logs.get.size shouldBe (size) // the number of activations in this sequence
        var totalTime: Long = 0
        var maxMemory: Long = 0
        for (id <- activation.logs.get) {
            withActivation(wsk.activation, id, initialWait = 1 second, pollPeriod = 60 seconds, totalWait = allowedActionDuration) {
                componentActivation =>
                    componentActivation.cause shouldBe defined
                    componentActivation.cause.get shouldBe (activation.activationId)
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
    private def checkLogsAtomicAction(atomicActionIdx: Int, run: RunResult, regex: Regex) {
        withActivation(wsk.activation, run, totalWait = 2 * allowedActionDuration) { activation =>
            checkSequenceLogsAndAnnotations(activation, 1)
            val componentId = activation.logs.get(atomicActionIdx)
            val getComponentActivation = wsk.activation.get(componentId)
            withActivation(wsk.activation, getComponentActivation, totalWait = allowedActionDuration) { componentActivation =>
                println(componentActivation)
                componentActivation.logs shouldBe defined
                val logs = componentActivation.logs.get.mkString(" ")
                regex.findFirstIn(logs) shouldBe defined
            }
        }
    }

    private def extractMemoryAnnotation(activation: CliActivation): Long = {
        val limits = activation.getAnnotationValue("limits")
        limits shouldBe defined
        limits.get.asJsObject.getFields("memory")(0).convertTo[Long]
    }
}

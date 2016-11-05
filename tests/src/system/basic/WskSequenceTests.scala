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
import common.Wsk
import common.WskProps
import common.WskTestHelpers
import spray.json.DefaultJsonProtocol._
import spray.json._

/**
 * Tests sequence execution
 */
@RunWith(classOf[JUnitRunner])
class WskSequenceTests
    extends TestHelpers
    with JsHelpers
    with WskTestHelpers {

    implicit val wskprops = WskProps()
    val wsk = new Wsk
    val allowedActionDuration = 120 seconds
    val defaultNamespace = wskprops.namespace

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

            assetHelper.withCleaner(wsk.action, name) {
                val sequence = actions.mkString (",")
                (action, _) => action.create(name, Some(sequence), kind = Some("sequence"), timeout = Some(allowedActionDuration))
            }

            val now = "it is now " + new Date()
            val args = Array("what time is it?", now)
            val run = wsk.action.invoke(name, Map("payload" -> args.mkString("\n").toJson))
            withActivation(wsk.activation, run, totalWait = 4 * allowedActionDuration) {
                activation =>
                    checkSequenceLogs(activation, 4) // 4 activations in this sequence
                    activation.cause shouldBe None   // topmost sequence
                    val result = activation.response.result.get
                    result.fields.get("payload") shouldBe defined
                    result.fields.get("length") should not be defined
                    result.fields.get("lines") shouldBe Some(JsArray(Vector(now.toJson)))
            }

            // update action sequence and run it with normal payload
            val newSequence = Seq("split", "sort").mkString (",")
            wsk.action.create(name, Some(newSequence), kind = Some("sequence"), timeout = Some(allowedActionDuration), update = true)
            val secondrun = wsk.action.invoke(name, Map("payload" -> args.mkString("\n").toJson))
            withActivation(wsk.activation, secondrun, totalWait = 2 * allowedActionDuration) {
                activation =>
                    checkSequenceLogs(activation, 2) // 2 activations in this sequence
                    val result = activation.response.result.get
                    result.fields.get("length") shouldBe Some(2.toJson)
                    result.fields.get("lines") shouldBe Some(args.sortWith(_.compareTo(_) < 0).toArray.toJson)
            }

            // run sequence with error in the payload; nothing should run
            val payload = Map("payload" -> args.mkString("\n").toJson, "error" -> JsString("irrelevant error string"))
            val thirdrun = wsk.action.invoke(name, payload)
            withActivation(wsk.activation, thirdrun, totalWait = allowedActionDuration) {
                activation =>
                    activation.logs shouldBe defined
                    // no activations should have run
                    activation.logs.get.size shouldBe(0)
                    // the result of the activation should be the payload
                    println(activation.response)
                    activation.response.success shouldBe(false)
                    activation.response.status shouldBe("application error")
                    val result = activation.response.result.get
                    result shouldBe(payload.toJson)
                    // the status should be error
            }
    }

    /**
     * checks logs for the activation of a sequence (length/size and ids)
     * checks that the cause field for composing atomic actions is set properly
     * checks duration
     * checks memory (TODO)
     */
    private def checkSequenceLogs(activation: CliActivation, size: Int) = {
        activation.logs shouldBe defined
        // check that the logs are what they are supposed to be (activation ids)
        // check that the cause field is properly set for these activations
        activation.logs.get.size shouldBe(size) // the number of activations in this sequence
        var totalTime: Long = 0
        var maxMemory: Int = 0
        for (id <- activation.logs.get) {
            val getComponentActivation = wsk.activation.get(id)
            withActivation(wsk.activation, getComponentActivation, totalWait = allowedActionDuration) {
                componentActivation =>
                    componentActivation.cause shouldBe defined
                    componentActivation.cause.get shouldBe(activation.activationId)
                    totalTime += (componentActivation.end - componentActivation.start)
                    // extract memory
                    val limits = componentActivation.getAnnotationValue("limits")
                    limits shouldBe defined
                    val mem = limits.get.asJsObject.getFields("memory")(0).convertTo[Int]
                    maxMemory = maxMemory max mem
            }
        }
        // extract duration
        activation.annotations shouldBe defined
        val duration = activation.getAnnotationValue("duration")
        duration shouldBe defined
        duration.get.convertTo[Long] shouldBe(totalTime)
        // extract memory
//        val memory = activation.getAnnotationValue("memory")
//        memory shouldBe defined
//        memory.get.convertTo[Int] shouldBe(maxMemory)
    }
}

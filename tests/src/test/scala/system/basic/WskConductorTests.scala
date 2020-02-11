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

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import common._
import common.rest.WskRestOperations
import spray.json._
import spray.json.DefaultJsonProtocol._
import org.apache.openwhisk.core.entity.size.SizeInt
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.http.Messages._

@RunWith(classOf[JUnitRunner])
class WskConductorTests extends TestHelpers with WskTestHelpers with JsHelpers with StreamLogging with WskActorSystem {

  implicit val wskprops = WskProps()
  val wsk: WskOperations = new WskRestOperations

  val allowedActionDuration = 120 seconds

  val testString = "this is a test"
  val invalid = "invalid#Action"
  val missing = "missingAction"

  val whiskConfig = new WhiskConfig(Map(WhiskConfig.actionSequenceMaxLimit -> "50"))
  val limit = whiskConfig.actionSequenceLimit.toInt

  behavior of "Whisk conductor actions"

  it should "invoke a conductor action with no continuation" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val echo = "echo" // echo conductor action
    assetHelper.withCleaner(wsk.action, echo) { (action, _) =>
      action.create(
        echo,
        Some(TestUtils.getTestActionFilename("echo.js")),
        annotations = Map("conductor" -> true.toJson))
    }

    // the conductor annotation should not affect the behavior of an action
    // that returns a dictionary without a params or action field
    val run = wsk.action.invoke(echo, Map("payload" -> testString.toJson, "state" -> testString.toJson))
    withActivation(wsk.activation, run) { activation =>
      activation.response.status shouldBe "success"
      activation.response.result shouldBe Some(JsObject("payload" -> testString.toJson, "state" -> testString.toJson))
      checkConductorLogsAndAnnotations(activation, 1) // echo
    }

    // the conductor annotation should not affect the behavior of an action that returns an error
    val secondrun = wsk.action.invoke(echo, Map("error" -> testString.toJson))
    withActivation(wsk.activation, secondrun) { activation =>
      activation.response.status shouldBe "application error"
      activation.response.result shouldBe Some(JsObject("error" -> testString.toJson))
      checkConductorLogsAndAnnotations(activation, 1) // echo
    }

    // the controller should unwrap a wrapped result { params: result, ... } for an action with a conductor annotation
    // discarding other fields if there is no action field
    val thirdrun = wsk.action.invoke(
      echo,
      Map(
        "params" -> JsObject("payload" -> testString.toJson),
        "result" -> testString.toJson,
        "state" -> testString.toJson))
    withActivation(wsk.activation, thirdrun) { activation =>
      activation.response.status shouldBe "success"
      activation.response.result shouldBe Some(JsObject("payload" -> testString.toJson))
      checkConductorLogsAndAnnotations(activation, 1) // echo
    }
  }

  it should "invoke a conductor action with an invalid continuation" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val echo = "echo" // echo conductor action
      assetHelper.withCleaner(wsk.action, echo) { (action, _) =>
        action.create(
          echo,
          Some(TestUtils.getTestActionFilename("echo.js")),
          annotations = Map("conductor" -> true.toJson))
      }

      // an invalid action name
      val invalidrun =
        wsk.action.invoke(echo, Map("payload" -> testString.toJson, "action" -> invalid.toJson))
      withActivation(wsk.activation, invalidrun) { activation =>
        activation.response.status shouldBe "application error"
        activation.response.result.get.fields.get("error") shouldBe Some(
          JsString(compositionComponentInvalid(JsString(invalid))))
        checkConductorLogsAndAnnotations(activation, 2) // echo
      }

      // an undefined action
      val undefinedrun = wsk.action.invoke(echo, Map("payload" -> testString.toJson, "action" -> missing.toJson))
      val namespace = wsk.namespace.whois()

      withActivation(wsk.activation, undefinedrun) { activation =>
        activation.response.status shouldBe "application error"
        activation.response.result.get.fields.get("error") shouldBe Some(
          JsString(compositionComponentNotFound(s"$namespace/$missing")))
        checkConductorLogsAndAnnotations(activation, 2) // echo
      }
  }

  it should "invoke a conductor action with a continuation" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val conductor = "conductor" // conductor action
    assetHelper.withCleaner(wsk.action, conductor) { (action, _) =>
      action.create(
        conductor,
        Some(TestUtils.getTestActionFilename("conductor.js")),
        annotations = Map("conductor" -> true.toJson))
    }

    val step = "step" // step action with higher memory limit than conductor to test max memory computation
    assetHelper.withCleaner(wsk.action, step) { (action, _) =>
      action.create(step, Some(TestUtils.getTestActionFilename("step.js")), memory = Some(257 MB))
    }

    // dynamically invoke step action
    val run = wsk.action.invoke(conductor, Map("action" -> step.toJson, "n" -> 1.toJson))
    withActivation(wsk.activation, run) { activation =>
      activation.response.status shouldBe "success"
      activation.response.result shouldBe Some(JsObject("n" -> 2.toJson))
      checkConductorLogsAndAnnotations(activation, 3) // conductor, step, conductor
    }

    // dynamically invoke step action with an error result
    val errorrun = wsk.action.invoke(conductor, Map("action" -> step.toJson))
    withActivation(wsk.activation, errorrun) { activation =>
      activation.response.status shouldBe "application error"
      activation.response.result shouldBe Some(JsObject("error" -> JsString("missing parameter")))
      checkConductorLogsAndAnnotations(activation, 3) // conductor, step, conductor
    }

    // dynamically invoke step action, blocking invocation
    val blockingrun = wsk.action.invoke(conductor, Map("action" -> step.toJson, "n" -> 1.toJson), blocking = true)
    val activation = wsk.parseJsonString(blockingrun.stdout).convertTo[ActivationResult]

    withClue(s"check failed for blocking conductor activation: $activation") {
      activation.response.status shouldBe "success"
      activation.response.result shouldBe Some(JsObject("n" -> 2.toJson))
      checkConductorLogsAndAnnotations(activation, 3) // conductor, step, conductor
    }

    // dynamically invoke step action, forwarding state
    val secondrun = wsk.action.invoke(
      conductor,
      Map(
        "action" -> step.toJson, // invoke step
        "state" -> JsObject("witness" -> 42.toJson), // dummy state
        "n" -> 1.toJson))
    withActivation(wsk.activation, secondrun) { activation =>
      activation.response.status shouldBe "success"
      activation.response.result shouldBe Some(JsObject("n" -> 2.toJson, "witness" -> 42.toJson))
      checkConductorLogsAndAnnotations(activation, 3) // conductor, step, conductor
    }

    // dynamically invoke step action twice, forwarding state
    val thirdrun = wsk.action.invoke(
      conductor,
      Map(
        "action" -> step.toJson, // invoke step
        "state" -> JsObject("action" -> step.toJson), // invoke step again
        "n" -> 1.toJson))
    withActivation(wsk.activation, thirdrun) { activation =>
      activation.response.status shouldBe "success"
      activation.response.result shouldBe Some(JsObject("n" -> 3.toJson))
      checkConductorLogsAndAnnotations(activation, 5) // conductor, step, conductor, step, conductor
    }
  }

  it should "invoke nested conductor actions" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val conductor = "conductor" // conductor action
    assetHelper.withCleaner(wsk.action, conductor) { (action, _) =>
      action.create(
        conductor,
        Some(TestUtils.getTestActionFilename("conductor.js")),
        annotations = Map("conductor" -> true.toJson))
    }

    val step = "step" // step action with lower memory limit than conductor to test max memory computation
    assetHelper.withCleaner(wsk.action, step) { (action, _) =>
      action.create(step, Some(TestUtils.getTestActionFilename("step.js")), memory = Some(255 MB))
    }

    // invoke nested conductor with single step
    val run = wsk.action.invoke(
      conductor,
      Map(
        "action" -> conductor.toJson, // invoke nested conductor
        "params" -> JsObject("action" -> step.toJson), // invoke step (level 1)
        "n" -> 1.toJson))
    withActivation(wsk.activation, run) { activation =>
      activation.response.status shouldBe "success"
      activation.response.result shouldBe Some(JsObject("n" -> 2.toJson))
      checkConductorLogsAndAnnotations(activation, 3) // conductor, nested conductor, conductor
      // check nested conductor invocation
      withActivation(
        wsk.activation,
        activation.logs.get(1),
        initialWait = 1 second,
        pollPeriod = 60 seconds,
        totalWait = allowedActionDuration) { nestedActivation =>
        nestedActivation.response.status shouldBe "success"
        nestedActivation.response.result shouldBe Some(JsObject("n" -> 2.toJson))
        checkConductorLogsAndAnnotations(nestedActivation, 3) // conductor, step, conductor
      }
    }

    // invoke nested conductor with single step, blocking invocation
    val blockingrun = wsk.action.invoke(
      conductor,
      Map(
        "action" -> conductor.toJson, // invoke nested conductor
        "params" -> JsObject("action" -> step.toJson), // invoke step (level 1)
        "n" -> 1.toJson),
      blocking = true)
    val activation = wsk.parseJsonString(blockingrun.stdout).convertTo[ActivationResult]

    withClue(s"check failed for blocking conductor activation: $activation") {
      activation.response.status shouldBe "success"
      activation.response.result shouldBe Some(JsObject("n" -> 2.toJson))
      checkConductorLogsAndAnnotations(activation, 3) // conductor, nested conductor, conductor
    }

    // nested step followed by outer step
    val secondrun = wsk.action.invoke(
      conductor,
      Map(
        "action" -> conductor.toJson, // invoke nested conductor
        "state" -> JsObject("action" -> step.toJson), // invoked step on return of nested conductor (level 0)
        "params" -> JsObject("action" -> step.toJson), // invoke step (level 1)
        "n" -> 1.toJson))
    withActivation(wsk.activation, secondrun) { activation =>
      activation.response.status shouldBe "success"
      activation.response.result shouldBe Some(JsObject("n" -> 3.toJson))
      checkConductorLogsAndAnnotations(activation, 5)
    }

    // two levels of nesting, three steps
    val thirdrun = wsk.action.invoke(
      conductor,
      Map(
        "action" -> conductor.toJson, // invoke nested conductor
        "state" -> JsObject("action" -> step.toJson), // invoke step on return (level 0)
        "params" -> JsObject(
          "action" -> conductor.toJson, // invoked nested nested conductor
          "state" -> JsObject("action" -> step.toJson), // invoke step on return (level 1)
          "params" -> JsObject("action" -> step.toJson)), // invoke step (level 2)
        "n" -> 1.toJson))
    withActivation(wsk.activation, thirdrun) { activation =>
      activation.response.status shouldBe "success"
      activation.response.result shouldBe Some(JsObject("n" -> 4.toJson))
      checkConductorLogsAndAnnotations(activation, 5)
    }
  }

  it should "invoke a conductor action in a package binding" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val ns = wsk.namespace.whois()
    val actionName = "echo" // echo conductor action
    val packageName = "package1"
    val bindName = "package2"
    val packageActionName = packageName + "/" + actionName
    val bindActionName = bindName + "/" + actionName
    val bindNameWithNamespace = ns + "/" + bindName

    assetHelper.withCleaner(wsk.pkg, packageName) { (pkg, _) =>
      pkg.create(packageName)
    }
    assetHelper.withCleaner(wsk.pkg, bindName) { (pkg, _) =>
      pkg.bind(packageName, bindName)
    }

    assetHelper.withCleaner(wsk.action, packageActionName) { (action, _) =>
      action.create(
        packageActionName,
        Some(TestUtils.getTestActionFilename("echo.js")),
        annotations = Map("conductor" -> true.toJson))
    }

    // the conductor annotation should not affect the behavior of an action
    // that returns a dictionary without a params or action field
    val run = wsk.action.invoke(bindActionName, Map("payload" -> testString.toJson, "state" -> testString.toJson))
    withActivation(wsk.activation, run) { activation =>
      activation.response.status shouldBe "success"
      activation.response.result shouldBe Some(JsObject("payload" -> testString.toJson, "state" -> testString.toJson))

      val binding = activation.getAnnotationValue("binding")
      binding shouldBe defined
      binding.get shouldBe JsString(bindNameWithNamespace)

      checkConductorLogsAndAnnotations(activation, 1) // echo
    }

    // the conductor annotation should not affect the behavior of an action that returns an error
    val secondrun = wsk.action.invoke(bindActionName, Map("error" -> testString.toJson))
    withActivation(wsk.activation, secondrun) { activation =>
      activation.response.status shouldBe "application error"
      activation.response.result shouldBe Some(JsObject("error" -> testString.toJson))

      val binding = activation.getAnnotationValue("binding")
      binding shouldBe defined
      binding.get shouldBe JsString(bindNameWithNamespace)

      checkConductorLogsAndAnnotations(activation, 1) // echo
    }

    // the controller should unwrap a wrapped result { params: result, ... } for an action with a conductor annotation
    // discarding other fields if there is no action field
    val thirdrun = wsk.action.invoke(
      bindActionName,
      Map(
        "params" -> JsObject("payload" -> testString.toJson),
        "result" -> testString.toJson,
        "state" -> testString.toJson))
    withActivation(wsk.activation, thirdrun) { activation =>
      activation.response.status shouldBe "success"
      activation.response.result shouldBe Some(JsObject("payload" -> testString.toJson))

      val binding = activation.getAnnotationValue("binding")
      binding shouldBe defined
      binding.get shouldBe JsString(bindNameWithNamespace)

      checkConductorLogsAndAnnotations(activation, 1) // echo
    }
  }

  /**
   * checks logs for the activation of a conductor action (length/size and ids)
   * checks that the cause field for nested invocations is set properly
   * checks duration
   * checks memory
   */
  private def checkConductorLogsAndAnnotations(activation: ActivationResult, size: Int) = {
    activation.logs shouldBe defined
    // check that the logs are what they are supposed to be (activation ids)
    // check that the cause field is properly set for these activations
    activation.logs.get should have length size // the number of activations in this sequence
    var totalTime: Long = 0
    var maxMemory: Long = 0
    activation.logs.get.foreach { id =>
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

  private def extractMemoryAnnotation(activation: ActivationResult): Long = {
    val limits = activation.getAnnotationValue("limits")
    limits shouldBe defined
    limits.get.asJsObject.getFields("memory")(0).convertTo[Long]
  }
}

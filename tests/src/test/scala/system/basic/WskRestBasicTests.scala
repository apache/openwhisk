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

import akka.http.scaladsl.model.StatusCodes.Accepted
import akka.http.scaladsl.model.StatusCodes.BadGateway
import akka.http.scaladsl.model.StatusCodes.Conflict
import akka.http.scaladsl.model.StatusCodes.Unauthorized
import akka.http.scaladsl.model.StatusCodes.NotFound
import java.time.Instant

import scala.concurrent.duration.DurationInt
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import common._
import common.rest.WskRestOperations
import common.rest.RestResult
import spray.json._
import spray.json.DefaultJsonProtocol._
import org.apache.openwhisk.core.containerpool.Container
import org.apache.openwhisk.core.entity.Annotations
import org.apache.openwhisk.http.Messages

@RunWith(classOf[JUnitRunner])
class WskRestBasicTests extends TestHelpers with WskTestHelpers with WskActorSystem {

  implicit def wskprops: WskProps = WskProps()
  val wsk = new WskRestOperations

  val defaultAction: Some[String] = Some(TestUtils.getTestActionFilename("hello.js"))

  val requireAPIKeyAnnotation = WhiskProperties.getBooleanProperty("whisk.feature.requireApiKeyAnnotation", true);

  /**
   * Retry operations that need to settle the controller cache
   */
  def cacheRetry[T](fn: => T) = org.apache.openwhisk.utils.retry(fn, 5, Some(1.second))

  behavior of "Wsk REST"

  it should "reject creating duplicate entity" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "testDuplicateCreate"
    assetHelper.withCleaner(wsk.trigger, name) { (trigger, _) =>
      trigger.create(name)
    }
    assetHelper.withCleaner(wsk.action, name, confirmDelete = false) { (action, _) =>
      action.create(name, defaultAction, expectedExitCode = Conflict.intValue)
    }
  }

  it should "reject deleting entity in wrong collection" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "testCrossDelete"
    assetHelper.withCleaner(wsk.trigger, name) { (trigger, _) =>
      trigger.create(name)
    }
    wsk.action.delete(name, expectedExitCode = Conflict.intValue)
  }

  it should "reject unauthenticated access" in {
    implicit val wskprops = WskProps("xxx") // shadow properties
    val errormsg = "The supplied authentication is invalid"
    wsk.namespace.list(expectedExitCode = Unauthorized.intValue).stderr should include(errormsg)
  }

  behavior of "Wsk Package REST"

  it should "create, update, get and list a package" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "testPackage"
    val params = Map("a" -> "A".toJson)
    assetHelper.withCleaner(wsk.pkg, name) { (pkg, _) =>
      pkg.create(name, parameters = params, shared = Some(true))
      pkg.create(name, update = true)
    }

    // Add retry to ensure cache with "0.0.1" is replaced
    val pack = cacheRetry({
      val p = wsk.pkg.get(name)
      p.getField("version") shouldBe "0.0.2"
      p
    })
    pack.getFieldJsValue("publish") shouldBe JsTrue
    pack.getFieldJsValue("parameters") shouldBe JsArray(JsObject("key" -> JsString("a"), "value" -> JsString("A")))
    val packageList = wsk.pkg.list()
    val packages = packageList.getBodyListJsObject
    packages.exists(pack => RestResult.getField(pack, "name") == name) shouldBe true
  }

  it should "create, and get a package summary" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val runtime = "nodejs:default"
    val packageName = "packageName"
    val actionName = "actionName"
    val packageAnnots = Map(
      "description" -> JsString("Package description"),
      "parameters" -> JsArray(
        JsObject("name" -> JsString("paramName1"), "description" -> JsString("Parameter description 1")),
        JsObject("name" -> JsString("paramName2"), "description" -> JsString("Parameter description 2"))))
    val actionAnnots = Map(
      "description" -> JsString("Action description"),
      "parameters" -> JsArray(
        JsObject("name" -> JsString("paramName1"), "description" -> JsString("Parameter description 1")),
        JsObject("name" -> JsString("paramName2"), "description" -> JsString("Parameter description 2"))))

    assetHelper.withCleaner(wsk.pkg, packageName) { (pkg, _) =>
      pkg.create(packageName, annotations = packageAnnots)
    }

    wsk.action.create(packageName + "/" + actionName, defaultAction, annotations = actionAnnots, kind = Some(runtime))
    val result = cacheRetry({
      val p = wsk.pkg.get(packageName)
      p.getFieldListJsObject("actions") should have size 1
      p
    })
    val ns = wsk.namespace.whois()
    wsk.action.delete(packageName + "/" + actionName)

    result.getField("name") shouldBe packageName
    result.getField("namespace") shouldBe ns
    val annos = result.getFieldJsValue("annotations")
    annos shouldBe JsArray(
      JsObject("key" -> JsString("description"), "value" -> JsString("Package description")),
      JsObject(
        "key" -> JsString("parameters"),
        "value" -> JsArray(
          JsObject("name" -> JsString("paramName1"), "description" -> JsString("Parameter description 1")),
          JsObject("name" -> JsString("paramName2"), "description" -> JsString("Parameter description 2")))))
    val action = result.getFieldListJsObject("actions")(0)
    RestResult.getField(action, "name") shouldBe actionName

    val annoAction = RestResult.getFieldJsValue(action, "annotations")

    // first we check the returned execution runtime for 'nodejs:*'
    annoAction
      .convertTo[Seq[JsObject]]
      .find(_.fields("key").convertTo[String] == "exec")
      .map(_.fields("value"))
      .map(exec => { exec.convertTo[String] should startWith("nodejs:") })
      .getOrElse(fail())

    // since we checked it, we can remove the exec field from the annotations to make the following checks easier
    val annoActionWithoutExec =
      annoAction
        .convertTo[Seq[JsObject]]
        .filter(annotation => annotation.fields("key").convertTo[String] != "exec")
        .toJson

    annoActionWithoutExec shouldBe (if (requireAPIKeyAnnotation) {
                                      JsArray(
                                        JsObject(
                                          "key" -> JsString("description"),
                                          "value" -> JsString("Action description")),
                                        JsObject(
                                          "key" -> JsString("parameters"),
                                          "value" -> JsArray(
                                            JsObject(
                                              "name" -> JsString("paramName1"),
                                              "description" -> JsString("Parameter description 1")),
                                            JsObject(
                                              "name" -> JsString("paramName2"),
                                              "description" -> JsString("Parameter description 2")))),
                                        JsObject(
                                          "key" -> Annotations.ProvideApiKeyAnnotationName.toJson,
                                          "value" -> JsFalse))
                                    } else {
                                      JsArray(
                                        JsObject(
                                          "key" -> JsString("description"),
                                          "value" -> JsString("Action description")),
                                        JsObject(
                                          "key" -> JsString("parameters"),
                                          "value" -> JsArray(
                                            JsObject(
                                              "name" -> JsString("paramName1"),
                                              "description" -> JsString("Parameter description 1")),
                                            JsObject(
                                              "name" -> JsString("paramName2"),
                                              "description" -> JsString("Parameter description 2")))))
                                    })
  }

  it should "create a package with a name that contains spaces" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "package with spaces"

    assetHelper.withCleaner(wsk.pkg, name) { (pkg, _) =>
      pkg.create(name)
    }

    val pack = wsk.pkg.get(name)
    pack.getField("name") shouldBe name
  }

  it should "create a package, and get its individual fields" in withAssetCleaner(wskprops) {
    val name = "packageFields"
    val paramInput = Map("payload" -> "test".toJson)

    (wp, assetHelper) =>
      assetHelper.withCleaner(wsk.pkg, name) { (pkg, _) =>
        pkg.create(name, parameters = paramInput)
      }

      val expectedParam = JsObject("payload" -> JsString("test"))
      val ns = wsk.namespace.whois()

      var result = wsk.pkg.get(name)
      result.getField("namespace") shouldBe ns
      result.getField("name") shouldBe name
      result.getField("version") shouldBe "0.0.1"
      result.getFieldJsValue("publish") shouldBe JsFalse
      result.getFieldJsValue("binding") shouldBe JsObject.empty
      result.getField("invalid") shouldBe ""
  }

  it should "reject creation of duplication packages" in withAssetCleaner(wskprops) {
    val name = "dupePackage"

    (wp, assetHelper) =>
      assetHelper.withCleaner(wsk.pkg, name) { (pkg, _) =>
        pkg.create(name)
      }

      val stderr = wsk.pkg.create(name, expectedExitCode = Conflict.intValue).stderr
      stderr should include("resource already exists")
  }

  it should "reject delete of package that does not exist" in {
    val name = "nonexistentPackage"
    val stderr = wsk.pkg.delete(name, expectedExitCode = NotFound.intValue).stderr
    stderr should include("The requested resource does not exist.")
  }

  it should "reject get of package that does not exist" in {
    val name = "nonexistentPackage"
    val ns = wsk.namespace.whois()
    val stderr = wsk.pkg.get(name, expectedExitCode = NotFound.intValue).stderr
    stderr should include(s"The requested resource '$ns/$name' does not exist")
  }

  behavior of "Wsk Action REST"

  it should "create the same action twice with different cases" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    assetHelper.withCleaner(wsk.action, "TWICE") { (action, name) =>
      action.create(name, defaultAction)
    }
    assetHelper.withCleaner(wsk.action, "twice") { (action, name) =>
      action.create(name, defaultAction)
    }
  }

  it should "create, update, get and list an action" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "createAndUpdate"
    val file = Some(TestUtils.getTestActionFilename("hello.js"))
    val params = Map("a" -> "A".toJson)
    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, file, parameters = params)
      action.create(name, None, parameters = Map("b" -> "B".toJson), update = true)
    }

    // Add retry to ensure cache with "0.0.1" is replaced
    val action = cacheRetry({
      val a = wsk.action.get(name)
      a.getField("version") shouldBe "0.0.2"
      a
    })
    action.getFieldJsValue("parameters") shouldBe JsArray(JsObject("key" -> JsString("b"), "value" -> JsString("B")))
    action.getFieldJsValue("publish") shouldBe JsFalse
    val actionList = wsk.action.list()
    val actions = actionList.getBodyListJsObject
    actions.exists(action => RestResult.getField(action, "name") == name) shouldBe true
  }

  it should "reject create of an action that already exists" in withAssetCleaner(wskprops) {
    val name = "dupeAction"
    val file = Some(TestUtils.getTestActionFilename("echo.js"))

    (wp, assetHelper) =>
      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, file)
      }

      val stderr = wsk.action.create(name, file, expectedExitCode = Conflict.intValue).stderr
      stderr should include("resource already exists")
  }

  it should "reject delete of action that does not exist" in {
    val name = "nonexistentAction"
    val stderr = wsk.action.delete(name, expectedExitCode = NotFound.intValue).stderr
    stderr should include("The requested resource does not exist.")
  }

  it should "reject invocation of action that does not exist" in {
    val name = "nonexistentAction"
    val stderr = wsk.action.invoke(name, expectedExitCode = NotFound.intValue).stderr
    stderr should include("The requested resource does not exist.")
  }

  it should "reject get of an action that does not exist" in {
    val name = "nonexistentAction"
    val stderr = wsk.action.get(name, expectedExitCode = NotFound.intValue).stderr
    stderr should include("The requested resource does not exist.")
  }

  it should "create, and invoke an action that utilizes a docker container" in withAssetCleaner(wskprops) {
    val name = "dockerContainer"
    (wp, assetHelper) =>
      assetHelper.withCleaner(wsk.action, name) {
        // this docker image will be need to be pulled from dockerhub and hence has to be published there first
        (action, _) =>
          action.create(name, None, docker = Some("openwhisk/example"))
      }

      val args = Map("payload" -> "test".toJson)
      val run = wsk.action.invoke(name, args)
      withActivation(wsk.activation, run) { activation =>
        activation.response.result shouldBe Some(
          JsObject("args" -> args.toJson, "msg" -> "Hello from arbitrary C program!".toJson))
      }
  }

  it should "create, and invoke an action that utilizes dockerskeleton with native zip" in withAssetCleaner(wskprops) {
    val name = "dockerContainerWithZip"
    (wp, assetHelper) =>
      assetHelper.withCleaner(wsk.action, name) {
        // this docker image will be need to be pulled from dockerhub and hence has to be published there first
        (action, _) =>
          action.create(name, Some(TestUtils.getTestActionFilename("blackbox.zip")), kind = Some("native"))
      }

      val run = wsk.action.invoke(name, Map.empty)
      withActivation(wsk.activation, run) { activation =>
        activation.response.result shouldBe Some(JsObject("msg" -> "hello zip".toJson))
        activation.logs shouldBe defined
        val logs = activation.logs.get.toString
        logs should include("This is an example zip used with the docker skeleton action.")
        logs should not include Container.ACTIVATION_LOG_SENTINEL
      }
  }

  it should "create, and invoke an action using a parameter file" in withAssetCleaner(wskprops) {
    val name = "paramFileAction"
    val file = Some(TestUtils.getTestActionFilename("argCheck.js"))
    val argInput = Some(TestUtils.getTestActionFilename("validInput2.json"))

    (wp, assetHelper) =>
      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, file)
      }

      val expectedOutput = JsObject("payload" -> JsString("test"))
      val run = wsk.action.invoke(name, parameterFile = argInput)
      withActivation(wsk.activation, run) { activation =>
        activation.response.result shouldBe Some(expectedOutput)
      }
  }

  it should "create an action, and get its individual fields" in withAssetCleaner(wskprops) {
    val runtime = "nodejs:default"
    val name = "actionFields"
    val paramInput = Map("payload" -> "test".toJson)

    (wp, assetHelper) =>
      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, defaultAction, parameters = paramInput, kind = Some(runtime))
      }

      val expectedParam = JsObject("payload" -> JsString("test"))
      val ns = wsk.namespace.whois()
      val result = wsk.action.get(name)
      result.getField("name") shouldBe name
      result.getField("namespace") shouldBe ns
      result.getFieldJsValue("publish") shouldBe JsFalse
      result.getField("version") shouldBe "0.0.1"
      val exec = result.getFieldJsObject("exec")
      RestResult.getField(exec, "kind") should startWith("nodejs:")
      RestResult.getField(exec, "code") should not be ""
      result.getFieldJsValue("parameters") shouldBe JsArray(
        JsObject("key" -> JsString("payload"), "value" -> JsString("test")))

      // first we check the returned execution runtime for 'nodejs:*'
      result
        .getFieldJsValue("annotations")
        .convertTo[Seq[JsObject]]
        .find(_.fields("key").convertTo[String] == "exec")
        .map(_.fields("value"))
        .map(exec => { exec.convertTo[String] should startWith("nodejs:") })
        .getOrElse(fail())

      // since we checked it, we can remove the exec field from the annotations to make the following checks easier
      result
        .getFieldJsValue("annotations")
        .convertTo[Seq[JsObject]]
        .filter(annotation => annotation.fields("key").convertTo[String] != "exec")
        .toJson shouldBe (if (requireAPIKeyAnnotation) {
                            JsArray(
                              JsObject("key" -> Annotations.ProvideApiKeyAnnotationName.toJson, "value" -> JsFalse))
                          } else {
                            JsArray()
                          })
      result.getFieldJsValue("limits") shouldBe JsObject(
        "timeout" -> JsNumber(60000),
        "memory" -> JsNumber(256),
        "logs" -> JsNumber(10),
        "concurrency" -> JsNumber(1))
      result.getField("invalid") shouldBe ""
  }

  /**
   * Tests creating an action from a malformed js file. This should fail in
   * some way - preferably when trying to create the action. If not, then
   * surely when it runs there should be some indication in the logs. Don't
   * think this is true currently.
   */
  it should "create and invoke action with malformed js resulting in activation error" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "MALFORMED"
      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, Some(TestUtils.getTestActionFilename("malformed.js")))
      }

      val run = wsk.action.invoke(name, Map("payload" -> "whatever".toJson))
      withActivation(wsk.activation, run) { activation =>
        activation.response.status shouldBe "action developer error"
        // representing nodejs giving an error when given malformed.js
        activation.response.result.get.toString should include("ReferenceError")
      }
  }

  it should "create and invoke a blocking action resulting in an application error response" in withAssetCleaner(
    wskprops) { (wp, assetHelper) =>
    val name = "applicationError"
    val strErrInput = Map("error" -> "Error message".toJson)
    val numErrInput = Map("error" -> 502.toJson)
    val boolErrInput = Map("error" -> true.toJson)

    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, Some(TestUtils.getTestActionFilename("echo.js")))
    }

    Seq(strErrInput, numErrInput, boolErrInput) foreach { input =>
      val result = wsk.action.invoke(name, parameters = input, blocking = true, expectedExitCode = BadGateway.intValue)
      val response = result.getFieldJsObject("response")
      val res = RestResult.getFieldJsObject(response, "result")
      res shouldBe input.toJson.asJsObject
      val resultTrue =
        wsk.action.invoke(
          name,
          parameters = input,
          blocking = true,
          result = true,
          expectedExitCode = BadGateway.intValue)
      resultTrue.respData shouldBe input.toJson.asJsObject.toString()
    }
  }

  it should "create and invoke a blocking action resulting in an failed promise" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "errorResponseObject"
      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, Some(TestUtils.getTestActionFilename("asyncError.js")))
      }

      val result = wsk.action.invoke(name, blocking = true, expectedExitCode = BadGateway.intValue)
      val response = result.getFieldJsObject("response")
      val res = RestResult.getFieldJsObject(response, "result")
      res shouldBe JsObject("error" -> JsObject("msg" -> "failed activation on purpose".toJson))
  }

  it should "invoke a blocking action and get only the result" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "basicInvoke"
    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, Some(TestUtils.getTestActionFilename("wc.js")))
    }

    val result = wsk.action
      .invoke(name, Map("payload" -> "one two three".toJson), blocking = true, result = true)
    result.stdout.parseJson.asJsObject shouldBe JsObject("count" -> JsNumber(3))
  }

  it should "create, and get an action summary" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val runtime = "nodejs:default"
    val name = "actionName"
    val annots = Map(
      "description" -> JsString("Action description"),
      "parameters" -> JsArray(
        JsObject("name" -> JsString("paramName1"), "description" -> JsString("Parameter description 1")),
        JsObject("name" -> JsString("paramName2"), "description" -> JsString("Parameter description 2"))))

    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, defaultAction, annotations = annots, kind = Some(runtime))
    }

    val result = wsk.action.get(name, summary = true)
    val ns = wsk.namespace.whois()

    result.getField("name") shouldBe name
    result.getField("namespace") shouldBe ns

    val annos = result.getFieldJsValue("annotations")

    // first we check the returned execution runtime for 'nodejs:*'
    annos
      .convertTo[Seq[JsObject]]
      .find(_.fields("key").convertTo[String] == "exec")
      .map(_.fields("value"))
      .map(exec => { exec.convertTo[String] should startWith("nodejs:") })
      .getOrElse(fail())

    // since we checked it, we can remove the exec field from the annotations to make the following checks easier
    val annosWithoutExec =
      annos.convertTo[Seq[JsObject]].filter(annotation => annotation.fields("key").convertTo[String] != "exec").toJson

    annosWithoutExec shouldBe (if (requireAPIKeyAnnotation) {
                                 JsArray(
                                   JsObject(
                                     "key" -> JsString("description"),
                                     "value" -> JsString("Action description")),
                                   JsObject(
                                     "key" -> JsString("parameters"),
                                     "value" -> JsArray(
                                       JsObject(
                                         "name" -> JsString("paramName1"),
                                         "description" -> JsString("Parameter description 1")),
                                       JsObject(
                                         "name" -> JsString("paramName2"),
                                         "description" -> JsString("Parameter description 2")))),
                                   JsObject(
                                     "key" -> Annotations.ProvideApiKeyAnnotationName.toJson,
                                     "value" -> JsFalse))
                               } else {
                                 JsArray(
                                   JsObject(
                                     "key" -> JsString("description"),
                                     "value" -> JsString("Action description")),
                                   JsObject(
                                     "key" -> JsString("parameters"),
                                     "value" -> JsArray(
                                       JsObject(
                                         "name" -> JsString("paramName1"),
                                         "description" -> JsString("Parameter description 1")),
                                       JsObject(
                                         "name" -> JsString("paramName2"),
                                         "description" -> JsString("Parameter description 2")))))
                               })
  }

  it should "create an action with a name that contains spaces" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "action with spaces"

    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, defaultAction)
    }

    wsk.action.get(name).getField("name") shouldBe name
  }

  it should "create an action, and invoke an action that returns an empty JSON object" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "emptyJSONAction"

      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, Some(TestUtils.getTestActionFilename("emptyJSONResult.js")))
      }

      val result = wsk.action.invoke(name, blocking = true, result = true)
      result.stdout.parseJson.asJsObject shouldBe JsObject.empty
  }

  it should "create, and invoke an action that times out to ensure the proper response is received" in withAssetCleaner(
    wskprops) { (wp, assetHelper) =>
    val name = "sleepAction-" + System.currentTimeMillis()
    // Must be larger than 60 seconds to see the expected exit code
    val allowedActionDuration = 120.seconds
    // Sleep time must be larger than 60 seconds to see the expected exit code
    // Set sleep time to a value smaller than allowedActionDuration to not raise a timeout
    val sleepTime = allowedActionDuration - 20.seconds
    val params = Map("sleepTimeInMs" -> sleepTime.toMillis.toJson)
    val res = assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, Some(TestUtils.getTestActionFilename("sleep.js")), timeout = Some(allowedActionDuration))
      action.invoke(name, parameters = params, result = true, expectedExitCode = Accepted.intValue)
    }
  }

  it should "create, and get docker action get ensure exec code is omitted" in withAssetCleaner(wskprops) {
    val name = "dockerContainer"
    (wp, assetHelper) =>
      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, None, docker = Some("fake-container"))
      }

      wsk.action.get(name).stdout should not include """"code""""
  }

  behavior of "Wsk Trigger REST"

  it should "create, update, get, fire and list trigger" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val ruleName = withTimestamp("r1toa1")
    val triggerName = withTimestamp("t1tor1")
    val actionName = withTimestamp("a1")
    val params = Map("a" -> "A".toJson)
    val ns = wsk.namespace.whois()

    assetHelper.withCleaner(wsk.trigger, triggerName) { (trigger, _) =>
      trigger.create(triggerName, parameters = params)
      trigger.create(triggerName, update = true)
    }

    assetHelper.withCleaner(wsk.action, actionName) { (action, name) =>
      action.create(name, defaultAction)
    }

    assetHelper.withCleaner(wsk.rule, ruleName) { (rule, name) =>
      rule.create(name, trigger = triggerName, action = actionName)
    }

    // Add retry to ensure cache with "0.0.1" is replaced
    val trigger = cacheRetry({
      val t = wsk.trigger.get(triggerName)
      t.getField("version") shouldBe "0.0.2"
      t
    })
    trigger.getFieldJsValue("parameters") shouldBe JsArray(JsObject("key" -> JsString("a"), "value" -> JsString("A")))
    trigger.getFieldJsValue("publish") shouldBe JsFalse

    val expectedRules = JsObject(
      ns + "/" + ruleName -> JsObject(
        "action" -> JsObject("name" -> JsString(actionName), "path" -> JsString(ns)),
        "status" -> JsString("active")))
    trigger.getFieldJsValue("rules") shouldBe expectedRules

    val dynamicParams = Map("t" -> "T".toJson)
    val run = wsk.trigger.fire(triggerName, dynamicParams)
    withActivation(wsk.activation, run) { activation =>
      activation.response.result shouldBe Some(dynamicParams.toJson)
      activation.duration shouldBe 0L // shouldn't exist but CLI generates it
      activation.end shouldBe Instant.EPOCH // shouldn't exist but CLI generates it
      activation.logs shouldBe defined
      activation.logs.get.size shouldBe 1

      val logEntry = activation.logs.get(0).parseJson.asJsObject
      val logs = JsArray(logEntry)
      val ruleActivationId: String = logEntry.getFields("activationId")(0).convertTo[String]
      val expectedLogs = JsArray(
        JsObject(
          "statusCode" -> JsNumber(0),
          "activationId" -> JsString(ruleActivationId),
          "success" -> JsTrue,
          "rule" -> JsString(ns + "/" + ruleName),
          "action" -> JsString(ns + "/" + actionName)))
      logs shouldBe expectedLogs
    }

    val runWithNoParams = wsk.trigger.fire(triggerName, Map.empty)
    withActivation(wsk.activation, runWithNoParams) { activation =>
      activation.response.result shouldBe Some(JsObject.empty)
      activation.duration shouldBe 0L // shouldn't exist but CLI generates it
      activation.end shouldBe Instant.EPOCH // shouldn't exist but CLI generates it
    }

    val triggerList = wsk.trigger.list()
    val triggers = triggerList.getBodyListJsObject
    triggers.exists(trigger => RestResult.getField(trigger, "name") == triggerName) shouldBe true
  }

  it should "create, and get a trigger summary" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "triggerName"
    val annots = Map(
      "description" -> JsString("Trigger description"),
      "parameters" -> JsArray(
        JsObject("name" -> JsString("paramName1"), "description" -> JsString("Parameter description 1")),
        JsObject("name" -> JsString("paramName2"), "description" -> JsString("Parameter description 2"))))

    assetHelper.withCleaner(wsk.trigger, name) { (trigger, _) =>
      trigger.create(name, annotations = annots)
    }

    val result = wsk.trigger.get(name)
    val ns = wsk.namespace.whois()

    result.getField("name") shouldBe name
    result.getField("namespace") shouldBe ns
    val annos = result.getFieldJsValue("annotations")
    annos shouldBe JsArray(
      JsObject("key" -> JsString("description"), "value" -> JsString("Trigger description")),
      JsObject(
        "key" -> JsString("parameters"),
        "value" -> JsArray(
          JsObject("name" -> JsString("paramName1"), "description" -> JsString("Parameter description 1")),
          JsObject("name" -> JsString("paramName2"), "description" -> JsString("Parameter description 2")))))
  }

  it should "create a trigger with a name that contains spaces" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "trigger with spaces"

    assetHelper.withCleaner(wsk.trigger, name) { (trigger, _) =>
      trigger.create(name)
    }

    val res = wsk.trigger.get(name)
    res.getField("name") shouldBe name
  }

  it should "create, and fire a trigger using a parameter file" in withAssetCleaner(wskprops) {
    val ruleName = withTimestamp("r1toa1")
    val triggerName = withTimestamp("paramFileTrigger")
    val actionName = withTimestamp("a1")
    val argInput = Some(TestUtils.getTestActionFilename("validInput2.json"))

    (wp, assetHelper) =>
      assetHelper.withCleaner(wsk.trigger, triggerName) { (trigger, _) =>
        trigger.create(triggerName)
      }

      assetHelper.withCleaner(wsk.action, actionName) { (action, name) =>
        action.create(name, defaultAction)
      }

      assetHelper.withCleaner(wsk.rule, ruleName) { (rule, name) =>
        rule.create(name, trigger = triggerName, action = actionName)
      }

      val expectedOutput = JsObject("payload" -> JsString("test"))
      val run = wsk.trigger.fire(triggerName, parameterFile = argInput)
      withActivation(wsk.activation, run) { activation =>
        activation.response.result shouldBe Some(expectedOutput)
      }
  }

  it should "create a trigger, and get its individual fields" in withAssetCleaner(wskprops) {
    val name = "triggerFields"
    val paramInput = Map("payload" -> "test".toJson)

    (wp, assetHelper) =>
      assetHelper.withCleaner(wsk.trigger, name) { (trigger, _) =>
        trigger.create(name, parameters = paramInput)
      }

      val expectedParam = JsObject("payload" -> JsString("test"))
      val ns = wsk.namespace.whois()

      val result = wsk.trigger
        .get(name, fieldFilter = Some("namespace"))
      result.getField("namespace") shouldBe ns
      result.getField("name") shouldBe name
      result.getField("version") shouldBe "0.0.1"
      result.getFieldJsValue("publish") shouldBe JsFalse
      result.getFieldJsValue("annotations").toString shouldBe "[]"
      result.getFieldJsValue("parameters") shouldBe JsArray(
        JsObject("key" -> JsString("payload"), "value" -> JsString("test")))
      result.getFieldJsValue("limits") shouldBe JsObject.empty
      result.getField("invalid") shouldBe ""
  }

  it should "create, and fire a trigger to ensure result is empty" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val ruleName = withTimestamp("r1toa1")
    val triggerName = withTimestamp("emptyResultTrigger")
    val actionName = withTimestamp("a1")

    assetHelper.withCleaner(wsk.trigger, triggerName) { (trigger, _) =>
      trigger.create(triggerName)
    }

    assetHelper.withCleaner(wsk.action, actionName) { (action, name) =>
      action.create(name, defaultAction)
    }

    assetHelper.withCleaner(wsk.rule, ruleName) { (rule, name) =>
      rule.create(name, trigger = triggerName, action = actionName)
    }

    val run = wsk.trigger.fire(triggerName)
    withActivation(wsk.activation, run) { activation =>
      activation.response.result shouldBe Some(JsObject.empty)
    }
  }

  it should "reject creation of duplicate triggers" in withAssetCleaner(wskprops) {
    val name = "dupeTrigger"

    (wp, assetHelper) =>
      assetHelper.withCleaner(wsk.trigger, name) { (trigger, _) =>
        trigger.create(name)
      }

      val stderr = wsk.trigger.create(name, expectedExitCode = Conflict.intValue).stderr
      stderr should include("resource already exists")
  }

  it should "reject delete of trigger that does not exist" in {
    val name = "nonexistentTrigger"
    val stderr = wsk.trigger.delete(name, expectedExitCode = NotFound.intValue).stderr
    stderr should include("The requested resource does not exist.")
  }

  it should "reject get of trigger that does not exist" in {
    val name = "nonexistentTrigger"
    val stderr = wsk.trigger.get(name, expectedExitCode = NotFound.intValue).stderr
    stderr should include("The requested resource does not exist.")
  }

  it should "reject firing of a trigger that does not exist" in {
    val name = "nonexistentTrigger"
    val stderr = wsk.trigger.fire(name, expectedExitCode = NotFound.intValue).stderr
    stderr should include("The requested resource does not exist.")
  }

  it should "create and fire a trigger with a rule whose action has been deleted" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val ruleName1 = withTimestamp("r1toa1")
      val ruleName2 = withTimestamp("r2toa2")
      val triggerName = withTimestamp("t1tor1r2")
      val actionName1 = withTimestamp("a1")
      val actionName2 = withTimestamp("a2")
      val ns = wsk.namespace.whois()

      assetHelper.withCleaner(wsk.trigger, triggerName) { (trigger, _) =>
        trigger.create(triggerName)
        trigger.create(triggerName, update = true)
      }

      assetHelper.withCleaner(wsk.action, actionName1) { (action, name) =>
        action.create(name, defaultAction)
      }
      wsk.action.create(actionName2, defaultAction) // Delete this after the rule is created

      assetHelper.withCleaner(wsk.rule, ruleName1) { (rule, name) =>
        rule.create(name, trigger = triggerName, action = actionName1)
      }
      assetHelper.withCleaner(wsk.rule, ruleName2) { (rule, name) =>
        rule.create(name, trigger = triggerName, action = actionName2)
      }
      wsk.action.delete(actionName2)

      val run = wsk.trigger.fire(triggerName)
      withActivation(wsk.activation, run) { activation =>
        activation.duration shouldBe 0L // shouldn't exist but CLI generates it
        activation.end shouldBe Instant.EPOCH // shouldn't exist but CLI generates it
        activation.logs shouldBe defined
        activation.logs.get.size shouldBe 2

        val logEntry1 = activation.logs.get(0).parseJson.asJsObject
        val logEntry2 = activation.logs.get(1).parseJson.asJsObject
        val logs = JsArray(logEntry1, logEntry2)
        val ruleActivationId: String = if (logEntry1.getFields("activationId").size == 1) {
          logEntry1.getFields("activationId")(0).convertTo[String]
        } else {
          logEntry2.getFields("activationId")(0).convertTo[String]
        }
        val expectedLogs = JsArray(
          JsObject(
            "statusCode" -> JsNumber(0),
            "activationId" -> JsString(ruleActivationId),
            "success" -> JsTrue,
            "rule" -> JsString(ns + "/" + ruleName1),
            "action" -> JsString(ns + "/" + actionName1)),
          JsObject(
            "statusCode" -> JsNumber(1),
            "success" -> JsFalse,
            "error" -> JsString("The requested resource does not exist."),
            "rule" -> JsString(ns + "/" + ruleName2),
            "action" -> JsString(ns + "/" + actionName2)))
        logs shouldBe expectedLogs
      }
  }

  it should "create and fire a trigger having an active rule and an inactive rule" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val ruleName1 = withTimestamp("r1toa1")
      val ruleName2 = withTimestamp("r2toa2")
      val triggerName = withTimestamp("t1tor1r2")
      val actionName1 = withTimestamp("a1")
      val actionName2 = withTimestamp("a2")
      val ns = wsk.namespace.whois()

      assetHelper.withCleaner(wsk.trigger, triggerName) { (trigger, _) =>
        trigger.create(triggerName)
        trigger.create(triggerName, update = true)
      }

      assetHelper.withCleaner(wsk.action, actionName1) { (action, name) =>
        action.create(name, defaultAction)
      }
      assetHelper.withCleaner(wsk.action, actionName2) { (action, name) =>
        action.create(name, defaultAction)
      }

      assetHelper.withCleaner(wsk.rule, ruleName1) { (rule, name) =>
        rule.create(name, trigger = triggerName, action = actionName1)
      }
      assetHelper.withCleaner(wsk.rule, ruleName2) { (rule, name) =>
        rule.create(name, trigger = triggerName, action = actionName2)
        rule.disable(ruleName2)
      }

      val run = wsk.trigger.fire(triggerName)
      withActivation(wsk.activation, run) { activation =>
        activation.duration shouldBe 0L // shouldn't exist but CLI generates it
        activation.end shouldBe Instant.EPOCH // shouldn't exist but CLI generates it
        activation.logs shouldBe defined
        activation.logs.get.size shouldBe 2

        val logEntry1 = activation.logs.get(0).parseJson.asJsObject
        val logEntry2 = activation.logs.get(1).parseJson.asJsObject
        val logs = JsArray(logEntry1, logEntry2)
        val ruleActivationId: String = if (logEntry1.getFields("activationId").size == 1) {
          logEntry1.getFields("activationId")(0).convertTo[String]
        } else {
          logEntry2.getFields("activationId")(0).convertTo[String]
        }
        val expectedLogs = JsArray(
          JsObject(
            "statusCode" -> JsNumber(0),
            "activationId" -> JsString(ruleActivationId),
            "success" -> JsTrue,
            "rule" -> JsString(ns + "/" + ruleName1),
            "action" -> JsString(ns + "/" + actionName1)),
          JsObject(
            "statusCode" -> JsNumber(1),
            "success" -> JsFalse,
            "error" -> JsString(Messages.triggerWithInactiveRule(s"$ns/$ruleName2", s"$ns/$actionName2")),
            "rule" -> JsString(ns + "/" + ruleName2),
            "action" -> JsString(ns + "/" + actionName2)))
        logs shouldBe expectedLogs
      }
  }

  behavior of "Wsk Rule REST"

  it should "create rule, get rule, update rule and list rule" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val ruleName = "listRules"
    val triggerName = "listRulesTrigger"
    val actionName = "listRulesAction"

    assetHelper.withCleaner(wsk.trigger, triggerName) { (trigger, name) =>
      trigger.create(name)
    }
    assetHelper.withCleaner(wsk.action, actionName) { (action, name) =>
      action.create(name, defaultAction)
    }
    assetHelper.withCleaner(wsk.rule, ruleName) { (rule, name) =>
      rule.create(name, trigger = triggerName, action = actionName)
    }

    // finally, we perform the update, and expect success this time
    wsk.rule.create(ruleName, trigger = triggerName, action = actionName, update = true)

    // Add retry to ensure cache with "0.0.1" is replaced
    val rule = cacheRetry({
      val r = wsk.rule.get(ruleName)
      r.getField("version") shouldBe "0.0.2"
      r
    })
    rule.getField("name") shouldBe ruleName
    RestResult.getField(rule.getFieldJsObject("trigger"), "name") shouldBe triggerName
    RestResult.getField(rule.getFieldJsObject("action"), "name") shouldBe actionName
    val rules = wsk.rule.list().getBodyListJsObject
    rules.exists { rule =>
      RestResult.getField(rule, "name") == ruleName
    } shouldBe true
  }

  it should "create rule, get rule, ensure rule is enabled by default" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val ruleName = "enabledRule"
      val triggerName = "enabledRuleTrigger"
      val actionName = "enabledRuleAction"

      assetHelper.withCleaner(wsk.trigger, triggerName) { (trigger, name) =>
        trigger.create(name)
      }
      assetHelper.withCleaner(wsk.action, actionName) { (action, name) =>
        action.create(name, defaultAction)
      }
      assetHelper.withCleaner(wsk.rule, ruleName) { (rule, name) =>
        rule.create(name, trigger = triggerName, action = actionName)
      }

      val rule = wsk.rule.get(ruleName)
      rule.getField("status") shouldBe "active"
  }

  it should "display a rule summary when --summary flag is used with 'wsk rule get'" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val ruleName = "mySummaryRule"
      val triggerName = "summaryRuleTrigger"
      val actionName = "summaryRuleAction"

      assetHelper.withCleaner(wsk.trigger, triggerName) { (trigger, name) =>
        trigger.create(name)
      }
      assetHelper.withCleaner(wsk.action, actionName) { (action, name) =>
        action.create(name, defaultAction)
      }
      assetHelper.withCleaner(wsk.rule, ruleName, confirmDelete = false) { (rule, name) =>
        rule.create(name, trigger = triggerName, action = actionName)
      }

      // Summary namespace should match one of the allowable namespaces (typically 'guest')
      val ns = wsk.namespace.whois()
      val result = wsk.rule.get(ruleName)
      result.getField("name") shouldBe ruleName
      result.getField("namespace") shouldBe ns
      result.getField("status") shouldBe "active"
  }

  it should "create a rule, and get its individual fields" in withAssetCleaner(wskprops) {
    val ruleName = "ruleFields"
    val triggerName = "ruleTriggerFields"
    val actionName = "ruleActionFields"
    val paramInput = Map("payload" -> "test".toJson)

    (wp, assetHelper) =>
      assetHelper.withCleaner(wsk.trigger, triggerName) { (trigger, name) =>
        trigger.create(name)
      }
      assetHelper.withCleaner(wsk.action, actionName) { (action, name) =>
        action.create(name, defaultAction)
      }
      assetHelper.withCleaner(wsk.rule, ruleName) { (rule, name) =>
        rule.create(name, trigger = triggerName, action = actionName)
      }

      val ns = wsk.namespace.whois()
      val rule = wsk.rule.get(ruleName)
      rule.getField("namespace") shouldBe ns
      rule.getField("name") shouldBe ruleName
      rule.getField("version") shouldBe "0.0.1"
      rule.getField("status") shouldBe "active"
      val result = wsk.rule.get(ruleName)
      val trigger = result.getFieldJsValue("trigger").toString
      trigger should include(triggerName)
      trigger should not include actionName
      val action = result.getFieldJsValue("action").toString
      action should not include triggerName
      action should include(actionName)
  }

  it should "reject creation of duplicate rules" in withAssetCleaner(wskprops) {
    val ruleName = "dupeRule"
    val triggerName = "triggerName"
    val actionName = "actionName"

    (wp, assetHelper) =>
      assetHelper.withCleaner(wsk.trigger, triggerName) { (trigger, name) =>
        trigger.create(name)
      }
      assetHelper.withCleaner(wsk.action, actionName) { (action, name) =>
        action.create(name, defaultAction)
      }
      assetHelper.withCleaner(wsk.rule, ruleName) { (rule, name) =>
        rule.create(name, trigger = triggerName, action = actionName)
      }

      val stderr =
        wsk.rule
          .create(ruleName, trigger = triggerName, action = actionName, expectedExitCode = Conflict.intValue)
          .stderr
      stderr should include("resource already exists")
  }

  it should "reject delete of rule that does not exist" in {
    val name = "nonexistentRule"
    val stderr = wsk.rule.delete(name, expectedExitCode = NotFound.intValue).stderr
    stderr should include("The requested resource does not exist.")
  }

  it should "reject enable of rule that does not exist" in {
    val name = "nonexistentRule"
    val stderr = wsk.rule.enable(name, expectedExitCode = NotFound.intValue).stderr
    stderr should include("The requested resource does not exist.")
  }

  it should "reject disable of rule that does not exist" in {
    val name = "nonexistentRule"
    val stderr = wsk.rule.disable(name, expectedExitCode = NotFound.intValue).stderr
    stderr should include("The requested resource does not exist.")
  }

  it should "reject status of rule that does not exist" in {
    val name = "nonexistentRule"
    val stderr = wsk.rule.state(name, expectedExitCode = NotFound.intValue).stderr
    stderr should include("The requested resource does not exist.")
  }

  it should "reject get of rule that does not exist" in {
    val name = "nonexistentRule"
    val stderr = wsk.rule.get(name, expectedExitCode = NotFound.intValue).stderr
    stderr should include("The requested resource does not exist.")
  }

  behavior of "Wsk Namespace REST"

  it should "return a list of exactly one namespace" in {
    val lines = wsk.namespace.list()
    lines.getBodyListString.size shouldBe 1
  }

  behavior of "Wsk Activation REST"

  it should "create a trigger, and fire a trigger to get its individual fields from an activation" in withAssetCleaner(
    wskprops) { (wp, assetHelper) =>
    val ruleName = withTimestamp("r1toa1")
    val triggerName = withTimestamp("activationFields")
    val actionName = withTimestamp("a1")

    assetHelper.withCleaner(wsk.trigger, triggerName) { (trigger, _) =>
      trigger.create(triggerName)
    }

    assetHelper.withCleaner(wsk.action, actionName) { (action, name) =>
      action.create(name, defaultAction)
    }

    assetHelper.withCleaner(wsk.rule, ruleName) { (rule, name) =>
      rule.create(name, trigger = triggerName, action = actionName)
    }

    val ns = wsk.namespace.whois()
    val run = wsk.trigger.fire(triggerName)
    withActivation(wsk.activation, run) { activation =>
      var result = wsk.activation.get(Some(activation.activationId))
      result.getField("namespace") shouldBe ns
      result.getField("name") shouldBe triggerName
      result.getField("version") shouldBe "0.0.1"
      result.getFieldJsValue("publish") shouldBe JsFalse
      result.getField("subject") shouldBe ns
      result.getField("activationId") shouldBe activation.activationId
      result.getFieldJsValue("start").toString should not be JsObject.empty.toString
      result.getFieldJsValue("end").toString shouldBe JsObject.empty.toString
      result.getFieldJsValue("duration").toString shouldBe JsObject.empty.toString
      result.getFieldListJsObject("annotations").length shouldBe 0
    }
  }

  it should "reject get of activation that does not exist" in {
    val name = "0" * 32
    val stderr = wsk.activation.get(Some(name), expectedExitCode = NotFound.intValue).stderr
    stderr should include("The requested resource does not exist.")
  }

  it should "reject logs of activation that does not exist" in {
    val name = "0" * 32
    val stderr = wsk.activation.logs(Some(name), expectedExitCode = NotFound.intValue).stderr
    stderr should include("The requested resource does not exist.")
  }

  it should "reject result of activation that does not exist" in {
    val name = "0" * 32
    val stderr = wsk.activation.result(Some(name), expectedExitCode = NotFound.intValue).stderr
    stderr should include("The requested resource does not exist.")
  }
}

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

import java.util.Date

import scala.language.postfixOps
import scala.collection.mutable.HashMap
import scala.concurrent.duration.DurationInt
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import common._
import spray.json._
import spray.json.DefaultJsonProtocol.StringJsonFormat
import common.WskProps
import common.rest.WskRestOperations
import org.apache.openwhisk.core.entity.WhiskActivation

@RunWith(classOf[JUnitRunner])
class WskPackageTests extends TestHelpers with WskTestHelpers with WskActorSystem {

  implicit val wskprops = WskProps()
  val wsk: WskOperations = new WskRestOperations

  val LOG_DELAY = 80 seconds

  behavior of "Wsk Package"

  it should "allow creation and deletion of a package" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "simplepackage"
    assetHelper.withCleaner(wsk.pkg, name) { (pkg, _) =>
      pkg.create(name, Map.empty)
    }
  }

  val params1 = Map("p1" -> "v1".toJson, "p2" -> "".toJson)
  val params2 = Map("p1" -> "v1".toJson, "p2" -> "v2".toJson, "p3" -> "v3".toJson)

  it should "allow creation of a package with parameters" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "simplepackagewithparams"
    assetHelper.withCleaner(wsk.pkg, name) { (pkg, _) =>
      pkg.create(name, params1)
    }
  }

  it should "allow updating a package" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "simplepackagetoupdate"
    assetHelper.withCleaner(wsk.pkg, name) { (pkg, _) =>
      pkg.create(name, params1)
      pkg.create(name, params2, update = true)
    }
  }

  it should "allow binding of a package" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "simplepackagetobind"
    val bindName = "simplebind"
    assetHelper.withCleaner(wsk.pkg, name) { (pkg, _) =>
      pkg.create(name, params1)
    }
    assetHelper.withCleaner(wsk.pkg, bindName) { (pkg, _) =>
      pkg.bind(name, bindName, params2)
    }
  }

  it should "perform package binds so parameters are inherited" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
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

    // Check that the description of packages and actions includes all the inherited parameters.
    val packageDescription = wsk.pkg.get(packageName).stdout
    val bindDescription = wsk.pkg.get(bindName).stdout
    val packageActionDescription = wsk.action.get(packageActionName).stdout
    val bindActionDescription = wsk.action.get(bindActionName).stdout
    checkForParameters(packageDescription, packageParams)
    checkForParameters(bindDescription, packageParams, bindParams)
    checkForParameters(packageActionDescription, packageParams, actionParams)
    checkForParameters(bindActionDescription, packageParams, bindParams, actionParams)

    // Check that inherited parameters are passed to the action.
    val now = new Date().toString()
    val run = wsk.action.invoke(bindActionName, Map("payload" -> now.toJson))
    withActivation(wsk.activation, run, totalWait = LOG_DELAY) {
      _.logs.get.mkString(" ") should include regex (String
        .format(".*key0: value0.*key1a: value1a.*key1b: value2b.*key2a: value2a.*payload: %s", now))
    }
  }

  it should "contain an binding annotation if invoked action is in the package binding" in withAssetCleaner(wskprops) {
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

      val run = wsk.action.invoke(bindActionName)
      withActivation(wsk.activation, run, totalWait = LOG_DELAY) { activation =>
        val binding = activation.getAnnotationValue(WhiskActivation.bindingAnnotation)
        binding shouldBe defined
        binding.get shouldBe JsString(ns + "/" + bindName)
      }
  }

  it should "not contain an binding annotation if invoked action is not in the package binding" in withAssetCleaner(
    wskprops) { (wp, assetHelper) =>
    val packageName = "package1"
    val actionName = "print"
    val packageActionName = packageName + "/" + actionName

    val file = TestUtils.getTestActionFilename("echo.js")

    assetHelper.withCleaner(wsk.pkg, packageName) { (pkg, _) =>
      pkg.create(packageName)
    }
    assetHelper.withCleaner(wsk.action, packageActionName) { (action, _) =>
      action.create(packageActionName, Some(file))
    }
    assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
      action.create(actionName, Some(file))
    }

    withActivation(wsk.activation, wsk.action.invoke(packageActionName), totalWait = LOG_DELAY) { activation =>
      val binding = activation.getAnnotationValue(WhiskActivation.bindingAnnotation)
      binding shouldBe empty
    }
    withActivation(wsk.activation, wsk.action.invoke(actionName), totalWait = LOG_DELAY) { activation =>
      val binding = activation.getAnnotationValue(WhiskActivation.bindingAnnotation)
      binding shouldBe empty
    }
  }

  /**
   * Check that a description of an item includes the specified parameters.
   * Parameters keys in later parameter maps override earlier ones.
   */
  def checkForParameters(itemDescription: String, paramSets: Map[String, JsValue]*): Unit = {
    // Merge and the parameters handling overrides.
    val merged = HashMap.empty[String, JsValue]
    paramSets.foreach { merged ++= _ }
    val flatDescription = itemDescription.replace("\n", "").replace("\r", "")
    merged.foreach {
      case (key: String, value: JsValue) =>
        val toFind = s""""key":.*"${key}",.*"value":.*${value.toString}"""
        flatDescription should include regex toFind
    }
  }
}

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

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner

import common.TestHelpers
import common.TestUtils
import common.TestUtils.RunResult
import common.WskOperations
import common.WskProps
import common.WskTestHelpers
import spray.json._
import spray.json.DefaultJsonProtocol._
import org.apache.openwhisk.core.entity.Subject
import org.apache.openwhisk.core.entity.WhiskPackage
import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
abstract class WskEntitlementTests extends TestHelpers with WskTestHelpers with BeforeAndAfterAll {

  val wsk: WskOperations
  lazy val defaultWskProps = WskProps()
  lazy val guestWskProps = getAdditionalTestSubject(Subject().asString)
  val forbiddenCode: Int
  val timeoutCode: Int
  val notFoundCode: Int

  override def afterAll() = {
    disposeAdditionalTestSubject(guestWskProps.namespace)
  }

  def retry[A](block: => A) = org.apache.openwhisk.utils.retry(block, 10, Some(500.milliseconds))

  val samplePackage = "samplePackage"
  val sampleAction = "sampleAction"
  val fullSampleActionName = s"$samplePackage/$sampleAction"
  val guestNamespace = guestWskProps.namespace

  behavior of "Wsk Package Entitlement"

  it should "not allow unauthorized subject to operate on private action" in withAssetCleaner(guestWskProps) {
    (wp, assetHelper) =>
      val privateAction = "privateAction"

      assetHelper.withCleaner(wsk.action, privateAction) { (action, name) =>
        action.create(name, Some(TestUtils.getTestActionFilename("hello.js")))(wp)
      }

      val fullyQualifiedActionName = s"/$guestNamespace/$privateAction"
      wsk.action
        .get(fullyQualifiedActionName, expectedExitCode = forbiddenCode)(defaultWskProps)
        .stderr should include("not authorized")

      withAssetCleaner(defaultWskProps) { (wp, assetHelper) =>
        assetHelper.withCleaner(wsk.action, fullyQualifiedActionName, confirmDelete = false) { (action, name) =>
          val rr = action.create(name, None, update = true, expectedExitCode = forbiddenCode)(wp)
          rr.stderr should include("not authorized")
          rr
        }

        assetHelper.withCleaner(wsk.action, "unauthorized sequence", confirmDelete = false) { (action, name) =>
          val rr = action.create(
            name,
            Some(fullyQualifiedActionName),
            kind = Some("sequence"),
            update = true,
            expectedExitCode = forbiddenCode)(wp)
          rr.stderr should include("not authorized")
          rr
        }
      }

      wsk.action
        .delete(fullyQualifiedActionName, expectedExitCode = forbiddenCode)(defaultWskProps)
        .stderr should include("not authorized")

      wsk.action
        .invoke(fullyQualifiedActionName, expectedExitCode = forbiddenCode)(defaultWskProps)
        .stderr should include("not authorized")
  }

  it should "reject deleting action in shared package not owned by authkey" in withAssetCleaner(guestWskProps) {
    (wp, assetHelper) =>
      assetHelper.withCleaner(wsk.pkg, samplePackage) { (pkg, _) =>
        pkg.create(samplePackage, shared = Some(true))(wp)
      }

      assetHelper.withCleaner(wsk.action, fullSampleActionName) {
        val file = Some(TestUtils.getTestActionFilename("empty.js"))
        (action, _) =>
          action.create(fullSampleActionName, file)(wp)
      }

      val fullyQualifiedActionName = s"/$guestNamespace/$fullSampleActionName"
      wsk.action.get(fullyQualifiedActionName)(defaultWskProps)
      wsk.action.delete(fullyQualifiedActionName, expectedExitCode = forbiddenCode)(defaultWskProps)
  }

  it should "reject create action in shared package not owned by authkey" in withAssetCleaner(guestWskProps) {
    (wp, assetHelper) =>
      assetHelper.withCleaner(wsk.pkg, samplePackage) { (pkg, name) =>
        pkg.create(name, shared = Some(true))(wp)
      }

      val fullyQualifiedActionName = s"/$guestNamespace/notallowed"
      val file = Some(TestUtils.getTestActionFilename("empty.js"))

      withAssetCleaner(defaultWskProps) { (wp, assetHelper) =>
        assetHelper.withCleaner(wsk.action, fullyQualifiedActionName, confirmDelete = false) { (action, name) =>
          action.create(name, file, expectedExitCode = forbiddenCode)(wp)
        }
      }
  }

  it should "reject update action in shared package not owned by authkey" in withAssetCleaner(guestWskProps) {
    (wp, assetHelper) =>
      assetHelper.withCleaner(wsk.pkg, samplePackage) { (pkg, _) =>
        pkg.create(samplePackage, shared = Some(true))(wp)
      }

      assetHelper.withCleaner(wsk.action, fullSampleActionName) {
        val file = Some(TestUtils.getTestActionFilename("empty.js"))
        (action, _) =>
          action.create(fullSampleActionName, file)(wp)
      }

      val fullyQualifiedActionName = s"/$guestNamespace/$fullSampleActionName"
      wsk.action.create(fullyQualifiedActionName, None, update = true, expectedExitCode = forbiddenCode)(
        defaultWskProps)
  }

  behavior of "Wsk Package Listing"

  it should "list shared packages" in withAssetCleaner(guestWskProps) { (wp, assetHelper) =>
    assetHelper.withCleaner(wsk.pkg, samplePackage) { (pkg, _) =>
      pkg.create(samplePackage, shared = Some(true))(wp)
    }

    retry {
      val packageList = wsk.pkg.list(Some(s"/$guestNamespace"))(defaultWskProps)
      verifyPackageSharedList(packageList, guestNamespace, samplePackage)
    }
  }

  it should "list shared packages when package is turned into public" in withAssetCleaner(guestWskProps) {
    (wp, assetHelper) =>
      assetHelper.withCleaner(wsk.pkg, samplePackage) { (pkg, _) =>
        pkg.create(samplePackage)(wp)
      }

      retry {
        val packageList = wsk.pkg.list(Some(s"/$guestNamespace"))(defaultWskProps)
        verifyPackageNotSharedList(packageList, guestNamespace, samplePackage)
      }

      wsk.pkg.create(samplePackage, update = true, shared = Some(true))(wp)

      retry {
        val packageList = wsk.pkg.list(Some(s"/$guestNamespace"))(defaultWskProps)
        verifyPackageSharedList(packageList, guestNamespace, samplePackage)
      }
  }

  //TODO: convert to API-level test under whisk.core.controller once issues/3959 is resolved
  it should "reject getting package from invalid namespace" in withAssetCleaner(guestWskProps) { (wp, assetHelper) =>
    val invalidNamespace = "whisk.systsdf"
    wsk.pkg.get(s"/${invalidNamespace}/utils", expectedExitCode = forbiddenCode)(wp).stderr should include(
      "not authorized")
  }

  //TODO: convert to API-level test under whisk.core.controller once issues/3959 is resolved
  it should "reject getting invalid package from valid namespace" in withAssetCleaner(guestWskProps) {
    (wp, assetHelper) =>
      val invalidPackage = "utilssss"
      wsk.pkg.get(s"/whisk.system/${invalidPackage}", expectedExitCode = forbiddenCode)(wp).stderr should include(
        "not authorized")
  }

  def verifyPackageSharedList(packageList: RunResult, namespace: String, packageName: String): Unit = {
    val fullyQualifiedPackageName = s"/$namespace/$packageName"
    withClue(s"Packagelist is: ${packageList.stdout}; Packagename is: $fullyQualifiedPackageName")(
      packageList.stdout should include regex (fullyQualifiedPackageName + """\s+shared"""))
  }

  it should "not list private packages" in withAssetCleaner(guestWskProps) { (wp, assetHelper) =>
    assetHelper.withCleaner(wsk.pkg, samplePackage) { (pkg, _) =>
      pkg.create(samplePackage)(wp)
    }

    retry {
      val packageList = wsk.pkg.list(Some(s"/$guestNamespace"))(defaultWskProps)
      verifyPackageNotSharedList(packageList, guestNamespace, samplePackage)
    }
  }

  def verifyPackageNotSharedList(packageList: RunResult, namespace: String, packageName: String): Unit = {
    val fullyQualifiedPackageName = s"/$namespace/$packageName"
    withClue(s"Packagelist is: ${packageList.stdout}; Packagename is: $fullyQualifiedPackageName")(
      packageList.stdout should not include (fullyQualifiedPackageName))
  }

  it should "list shared package actions" in withAssetCleaner(guestWskProps) { (wp, assetHelper) =>
    assetHelper.withCleaner(wsk.pkg, samplePackage) { (pkg, _) =>
      pkg.create(samplePackage, shared = Some(true))(wp)
    }

    assetHelper.withCleaner(wsk.action, fullSampleActionName) {
      val file = Some(TestUtils.getTestActionFilename("empty.js"))
      (action, _) =>
        action.create(fullSampleActionName, file, kind = Some("nodejs:default"))(wp)
    }

    val fullyQualifiedPackageName = s"/$guestNamespace/$samplePackage"
    retry {
      val packageList = wsk.action.list(Some(fullyQualifiedPackageName))(defaultWskProps)
      verifyPackageList(packageList, guestNamespace, samplePackage, sampleAction)
    }
  }

  def verifyPackageList(packageList: RunResult, namespace: String, packageName: String, actionName: String): Unit = {
    val result = packageList.stdout
    result should include(s"/$namespace/$packageName/$actionName")
  }

  behavior of "Wsk Package Binding"

  it should "create a package binding" in withAssetCleaner(guestWskProps) { (wp, assetHelper) =>
    assetHelper.withCleaner(wsk.pkg, samplePackage) { (pkg, _) =>
      pkg.create(samplePackage, shared = Some(true))(wp)
    }

    val name = "bindPackage"
    val annotations = Map("a" -> "A".toJson, WhiskPackage.bindingFieldName -> "xxx".toJson)
    val provider = s"/$guestNamespace/$samplePackage"
    withAssetCleaner(defaultWskProps) { (wp, assetHelper) =>
      assetHelper.withCleaner(wsk.pkg, name) { (pkg, _) =>
        pkg.bind(provider, name, annotations = annotations)(wp)
      }

      val stdout = wsk.pkg.get(name)(defaultWskProps).stdout
      val annotationString = wsk.parseJsonString(stdout).fields("annotations").toString
      annotationString should include(""""key":"a"""")
      annotationString should include(""""value":"A"""")
      annotationString should include(s""""key":"${WhiskPackage.bindingFieldName}"""")
      annotationString should not include (""""key":"xxx"""")
      annotationString should include(s""""name":"${samplePackage}"""")
    }
  }

  it should "not create a package binding for private package" in withAssetCleaner(guestWskProps) { (wp, assetHelper) =>
    assetHelper.withCleaner(wsk.pkg, samplePackage) { (pkg, _) =>
      pkg.create(samplePackage, shared = Some(false))(wp)
    }

    val name = "bindPackage"
    val provider = s"/$guestNamespace/$samplePackage"
    withAssetCleaner(defaultWskProps) { (wp, assetHelper) =>
      assetHelper.withCleaner(wsk.pkg, name, confirmDelete = false) { (pkg, _) =>
        pkg.bind(provider, name, expectedExitCode = forbiddenCode)(wp)
      }
    }
  }

  behavior of "Wsk Package Action"

  it should "get and invoke an action from package" in withAssetCleaner(guestWskProps) { (wp, assetHelper) =>
    assetHelper.withCleaner(wsk.pkg, samplePackage) { (pkg, _) =>
      pkg.create(samplePackage, parameters = Map("a" -> "A".toJson), shared = Some(true))(wp)
    }

    assetHelper.withCleaner(wsk.action, fullSampleActionName) {
      val file = Some(TestUtils.getTestActionFilename("hello.js"))
      (action, _) =>
        action.create(fullSampleActionName, file)(wp)
    }

    val fullyQualifiedActionName = s"/$guestNamespace/$fullSampleActionName"
    val action = wsk.action.get(fullyQualifiedActionName)(defaultWskProps)
    verifyAction(action)

    val run = wsk.action.invoke(fullyQualifiedActionName)(defaultWskProps)

    withActivation(wsk.activation, run)({
      _.response.success shouldBe true
    })(defaultWskProps)
  }

  def verifyAction(action: RunResult) = {
    val stdout = action.stdout
    stdout should include("name")
    stdout should include("parameters")
    stdout should include("limits")
    stdout should include(""""key": "a"""")
    stdout should include(""""value": "A"""")
  }

  it should "invoke an action sequence from package" in withAssetCleaner(guestWskProps) { (wp, assetHelper) =>
    assetHelper.withCleaner(wsk.pkg, samplePackage) { (pkg, _) =>
      pkg.create(samplePackage, parameters = Map("a" -> "A".toJson), shared = Some(true))(wp)
    }

    assetHelper.withCleaner(wsk.action, fullSampleActionName) {
      val file = Some(TestUtils.getTestActionFilename("hello.js"))
      (action, _) =>
        action.create(fullSampleActionName, file)(wp)
    }

    withAssetCleaner(defaultWskProps) { (wp, assetHelper) =>
      assetHelper.withCleaner(wsk.action, "sequence") { (action, name) =>
        val fullyQualifiedActionName = s"/$guestNamespace/$fullSampleActionName"
        action.create(name, Some(fullyQualifiedActionName), kind = Some("sequence"), update = true)(wp)
      }

      val run = wsk.action.invoke("sequence")(defaultWskProps)
      withActivation(wsk.activation, run)({
        _.response.success shouldBe true
      })(defaultWskProps)
    }
  }

  it should "not allow invoke an action sequence with more than one component from package after entitlement change" in withAssetCleaner(
    guestWskProps) { (guestwp, assetHelper) =>
    val privateSamplePackage = samplePackage + "prv"
    Seq(samplePackage, privateSamplePackage).foreach { n =>
      assetHelper.withCleaner(wsk.pkg, n) { (pkg, _) =>
        pkg.create(n, parameters = Map("a" -> "A".toJson), shared = Some(true))(guestwp)
      }
    }

    Seq(fullSampleActionName, s"$privateSamplePackage/$sampleAction").foreach { a =>
      val file = Some(TestUtils.getTestActionFilename("hello.js"))
      assetHelper.withCleaner(wsk.action, a) { (action, _) =>
        action.create(a, file)(guestwp)
      }
    }

    withAssetCleaner(defaultWskProps) { (dwp, assetHelper) =>
      assetHelper.withCleaner(wsk.action, "sequence") { (action, name) =>
        val fullyQualifiedActionName = s"/$guestNamespace/$fullSampleActionName"
        val fullyQualifiedActionName2 = s"/$guestNamespace/$privateSamplePackage/$sampleAction"
        action.create(name, Some(s"$fullyQualifiedActionName,$fullyQualifiedActionName2"), kind = Some("sequence"))(dwp)
      }

      // change package visibility
      wsk.pkg.create(privateSamplePackage, update = true, shared = Some(false))(guestwp)
      wsk.action.invoke("sequence", expectedExitCode = forbiddenCode)(defaultWskProps)
    }
  }

  it should "invoke a packaged action not owned by the subject to get the subject's namespace" in withAssetCleaner(
    guestWskProps) { (_, assetHelper) =>
    val packageName = "namespacePackage"
    val actionName = "namespaceAction"
    val packagedActionName = s"$packageName/$actionName"

    assetHelper.withCleaner(wsk.pkg, packageName) { (pkg, _) =>
      pkg.create(packageName, shared = Some(true))(guestWskProps)
    }

    assetHelper.withCleaner(wsk.action, packagedActionName) {
      val file = Some(TestUtils.getTestActionFilename("helloContext.js"))
      (action, _) =>
        action.create(packagedActionName, file)(guestWskProps)
    }

    val fullyQualifiedActionName = s"/$guestNamespace/$packagedActionName"
    val run = wsk.action.invoke(fullyQualifiedActionName)(defaultWskProps)

    withActivation(wsk.activation, run)({ activation =>
      val namespace = wsk.namespace.whois()(defaultWskProps)
      activation.response.success shouldBe true
      activation.response.result.get.toString should include regex (s""""namespace":\\s*"$namespace"""")
    })(defaultWskProps)
  }

  behavior of "Wsk Trigger Feed"

  it should "not create a trigger with timeout error when feed fails to initialize" in withAssetCleaner(guestWskProps) {
    (wp, assetHelper) =>
      assetHelper.withCleaner(wsk.pkg, samplePackage) { (pkg, _) =>
        pkg.create(samplePackage, shared = Some(true))(wp)
      }

      val sampleFeed = s"$samplePackage/sampleFeed"
      assetHelper.withCleaner(wsk.action, sampleFeed) {
        val file = Some(TestUtils.getTestActionFilename("empty.js"))
        (action, _) =>
          action.create(sampleFeed, file, kind = Some("nodejs:default"))(wp)
      }

      val fullyQualifiedFeedName = s"/$guestNamespace/$sampleFeed"
      withAssetCleaner(defaultWskProps) { (wp, assetHelper) =>
        assetHelper.withCleaner(wsk.trigger, "badfeed", confirmDelete = false) { (trigger, name) =>
          trigger.create(name, feed = Some(fullyQualifiedFeedName), expectedExitCode = timeoutCode)(wp)
        }
        // with several active controllers race condition with cache invalidation might occur, thus retry
        retry(wsk.trigger.get("badfeed", expectedExitCode = notFoundCode)(wp))
      }
  }

}

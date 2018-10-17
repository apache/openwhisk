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
import org.scalatest.junit.JUnitRunner

import common.TestHelpers
import common.TestUtils
import common.WskOperations
import common.WskProps
import common.WskTestHelpers
import TestUtils.RunResult
import spray.json._
import org.apache.openwhisk.core.entity.EntityPath

/**
 * Tests creation and retrieval of a sequence action
 */
@RunWith(classOf[JUnitRunner])
abstract class WskActionSequenceTests extends TestHelpers with WskTestHelpers {

  implicit val wskprops = WskProps()
  val wsk: WskOperations
  val defaultNamespace = EntityPath.DEFAULT.asString
  lazy val namespace = wsk.namespace.whois()

  behavior of "Wsk Action Sequence"

  it should "create, and get an action sequence" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "actionSeq"
    val packageName = "samples"
    val helloName = "hello"
    val catName = "cat"
    val fullHelloActionName = s"/$defaultNamespace/$packageName/$helloName"
    val fullCatActionName = s"/$defaultNamespace/$packageName/$catName"

    assetHelper.withCleaner(wsk.pkg, packageName) { (pkg, _) =>
      pkg.create(packageName, shared = Some(true))(wp)
    }

    assetHelper.withCleaner(wsk.action, fullHelloActionName) {
      val file = Some(TestUtils.getTestActionFilename("hello.js"))
      (action, _) =>
        action.create(fullHelloActionName, file)(wp)
    }

    assetHelper.withCleaner(wsk.action, fullCatActionName) {
      val file = Some(TestUtils.getTestActionFilename("cat.js"))
      (action, _) =>
        action.create(fullCatActionName, file)(wp)
    }

    val artifacts = s"$fullHelloActionName,$fullCatActionName"
    val kindValue = JsString("sequence")
    val compValue = JsArray(
      JsString(resolveDefaultNamespace(fullHelloActionName)),
      JsString(resolveDefaultNamespace(fullCatActionName)))

    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, Some(artifacts), kind = Some("sequence"))
    }

    val action = wsk.action.get(name)
    verifyActionSequence(action, name, compValue, kindValue)
  }

  def verifyActionSequence(action: RunResult, name: String, compValue: JsArray, kindValue: JsString): Unit = {
    val stdout = action.stdout
    assert(stdout.startsWith(s"ok: got action $name\n"))
    wsk.parseJsonString(stdout).fields("exec").asJsObject.fields("components") shouldBe compValue
    wsk.parseJsonString(stdout).fields("exec").asJsObject.fields("kind") shouldBe kindValue
  }

  private def resolveDefaultNamespace(actionName: String) = actionName.replace("/_/", s"/$namespace/")
}

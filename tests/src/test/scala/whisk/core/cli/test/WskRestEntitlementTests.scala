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

package whisk.core.cli.test

import akka.http.scaladsl.model.StatusCodes.BadGateway
import akka.http.scaladsl.model.StatusCodes.Forbidden
import akka.http.scaladsl.model.StatusCodes.NotFound

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import common.rest.WskRest
import common.rest.RestResult
import common.TestUtils.RunResult

@RunWith(classOf[JUnitRunner])
class WskRestEntitlementTests extends WskEntitlementTests {
  override lazy val wsk: common.rest.WskRest = new WskRest
  override lazy val forbiddenCode = Forbidden.intValue
  override lazy val timeoutCode = BadGateway.intValue
  override lazy val notFoundCode = NotFound.intValue

  override def verifyAction(action: RunResult): org.scalatest.Assertion = {
    val stdout = action.stdout
    stdout should include("name")
    stdout should include("parameters")
    stdout should include("limits")
    stdout should include regex (""""key":"a"""")
    stdout should include regex (""""value":"A"""")
  }

  override def verifyPackageList(packageList: RunResult,
                                 namespace: String,
                                 packageName: String,
                                 actionName: String): Unit = {
    val packageListResultRest = packageList.asInstanceOf[RestResult]
    val packages = packageListResultRest.getBodyListJsObject()
    val ns = s"$namespace/$packageName"
    packages.exists(pack =>
      RestResult.getField(pack, "namespace") == ns && RestResult.getField(pack, "name") == actionName)
  }

  override def verifyPackageSharedList(packageList: RunResult, namespace: String, packageName: String): Unit = {
    val packageListResultRest = packageList.asInstanceOf[RestResult]
    val packages = packageListResultRest.getBodyListJsObject()
    packages.exists(pack =>
      RestResult.getField(pack, "namespace") == namespace && RestResult.getField(pack, "name") == packageName)
  }

  override def verifyPackageNotSharedList(packageList: RunResult, namespace: String, packageName: String): Unit = {
    val packageListResultRest = packageList.asInstanceOf[RestResult]
    val packages = packageListResultRest.getBodyListJsObject()
    packages.exists(pack => RestResult.getField(pack, "namespace") != namespace)
    packages.exists(pack => RestResult.getField(pack, "name") != packageName)
  }
}

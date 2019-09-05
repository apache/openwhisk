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

package org.apache.openwhisk.standalone

import common.WskProps
import org.apache.commons.lang3.SystemUtils
import org.junit.runner.RunWith
import org.scalatest.Pending
import org.scalatest.junit.JUnitRunner
import system.basic.WskRestBasicTests

@RunWith(classOf[JUnitRunner])
class StandaloneServerTests extends WskRestBasicTests with StandaloneServerFixture {
  override implicit val wskprops = WskProps().copy(apihost = serverUrl)

  //Following tests always fail on Mac but pass when standalone server is running on Linux
  //It looks related to how networking works on Mac for Docker container
  //For now ignoring there failure
  private val ignoredTestsOnMac = Set(
    "Wsk Action REST should create, and invoke an action that utilizes a docker container",
    "Wsk Action REST should create, and invoke an action that utilizes dockerskeleton with native zip",
    "Wsk Action REST should create and invoke a blocking action resulting in an application error response",
    "Wsk Action REST should create an action, and invoke an action that returns an empty JSON object")

  override def withFixture(test: NoArgTest) = {
    val outcome = super.withFixture(test)
    val result = if (outcome.isFailed && SystemUtils.IS_OS_MAC && ignoredTestsOnMac.contains(test.name)) {
      println(s"Ignoring known failed test for Mac [${test.name}]")
      Pending
    } else outcome
    result
  }
}

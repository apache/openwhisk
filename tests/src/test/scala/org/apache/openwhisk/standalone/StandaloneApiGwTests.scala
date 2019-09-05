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

import com.google.common.base.Stopwatch
import common.{FreePortFinder, WskProps}
import org.apache.openwhisk.core.cli.test.ApiGwRestTests
import org.apache.openwhisk.utils.retry
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class StandaloneApiGwTests extends ApiGwRestTests with StandaloneServerFixture {
  override implicit val wskprops = WskProps().copy(apihost = serverUrl)

  override protected def extraArgs: Seq[String] = Seq("--api-gw", "--api-gw-port", FreePortFinder.freePort().toString)

  override protected def waitForOtherThings(): Unit = {
    val w = Stopwatch.createStarted()
    retry({
      println(s"Waiting for route management actions to be installed since $w")
      require(logLines.exists(_.contains("Installed Route Management Actions")))
    }, 30, Some(500.millis))
  }

}

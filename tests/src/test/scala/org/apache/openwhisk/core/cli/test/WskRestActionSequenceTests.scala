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
import spray.json._
import common.rest.WskRestOperations
import common.rest.RestResult
import common.TestUtils.RunResult
import common.WskActorSystem

@RunWith(classOf[JUnitRunner])
class WskRestActionSequenceTests extends WskActionSequenceTests with WskActorSystem {
  override lazy val wsk = new WskRestOperations

  override def verifyActionSequence(action: RunResult, name: String, compValue: JsArray, kindValue: JsString): Unit = {
    val actionResultRest = action.asInstanceOf[RestResult]
    actionResultRest.respBody.fields("exec").asJsObject.fields("components") shouldBe compValue
    actionResultRest.respBody.fields("exec").asJsObject.fields("kind") shouldBe kindValue
  }
}

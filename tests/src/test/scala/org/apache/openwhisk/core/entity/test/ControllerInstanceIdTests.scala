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

package org.apache.openwhisk.core.entity.test

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import org.apache.openwhisk.core.entity.{ControllerInstanceId, InstanceId}
import spray.json.{JsObject, JsString}

import scala.util.Success

@RunWith(classOf[JUnitRunner])
class ControllerInstanceIdTests extends FlatSpec with Matchers {

  behavior of "ControllerInstanceId"

  it should "accept usable characters" in {
    Seq("a", "1", "a.1", "a_1").foreach { s =>
      ControllerInstanceId(s).asString shouldBe s

    }
  }

  it should "reject unusable characters" in {
    Seq(" ", "!", "$", "a" * 129).foreach { s =>
      an[IllegalArgumentException] shouldBe thrownBy {
        ControllerInstanceId(s)
      }
    }
  }

  it should "deserialize legacy ControllerInstanceId format" in {
    val i = ControllerInstanceId("controller0")
    ControllerInstanceId.parse(JsObject("asString" -> JsString("controller0")).compactPrint) shouldBe Success(i)
  }

  it should "serialize and deserialize ControllerInstanceId" in {
    val i = ControllerInstanceId("controller0")
    i.serialize shouldBe JsObject("asString" -> JsString(i.asString), "instanceType" -> JsString(i.instanceType)).compactPrint
    i.serialize shouldBe i.toJson.compactPrint
    InstanceId.parse(i.serialize) shouldBe Success(i)
  }

}

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

import org.apache.openwhisk.core.entity.size.SizeInt
import org.apache.openwhisk.core.entity.{ByteSize, InstanceId, InvokerInstanceId}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}
import spray.json.{JsNumber, JsObject, JsString}

import scala.util.Success

@RunWith(classOf[JUnitRunner])
class InvokerInstanceIdTests extends FlatSpec with Matchers {

  behavior of "InvokerInstanceIdTests"

  val defaultUserMemory: ByteSize = 1024.MB
  it should "serialize and deserialize InvokerInstanceId" in {
    val i = InvokerInstanceId(0, userMemory = defaultUserMemory)
    i.serialize shouldBe JsObject(
      "instance" -> JsNumber(i.instance),
      "userMemory" -> JsString(i.userMemory.toString),
      "instanceType" -> JsString(i.instanceType)).compactPrint
    i.serialize shouldBe i.toJson.compactPrint
    InstanceId.parse(i.serialize) shouldBe Success(i)
  }

  it should "serialize and deserialize InvokerInstanceId with optional field" in {
    val i1 = InvokerInstanceId(0, uniqueName = Some("uniqueInvoker"), userMemory = defaultUserMemory)
    i1.serialize shouldBe JsObject(
      "instance" -> JsNumber(i1.instance),
      "userMemory" -> JsString(i1.userMemory.toString),
      "instanceType" -> JsString(i1.instanceType),
      "uniqueName" -> JsString(i1.uniqueName.getOrElse(""))).compactPrint
    i1.serialize shouldBe i1.toJson.compactPrint
    InstanceId.parse(i1.serialize) shouldBe Success(i1)

    val i2 = InvokerInstanceId(
      0,
      uniqueName = Some("uniqueInvoker"),
      displayedName = Some("displayedInvoker"),
      userMemory = defaultUserMemory)
    i2.serialize shouldBe JsObject(
      "instance" -> JsNumber(i2.instance),
      "userMemory" -> JsString(i2.userMemory.toString),
      "instanceType" -> JsString(i2.instanceType),
      "uniqueName" -> JsString(i2.uniqueName.getOrElse("")),
      "displayedName" -> JsString(i2.displayedName.getOrElse(""))).compactPrint
    i2.serialize shouldBe i2.toJson.compactPrint
    InstanceId.parse(i2.serialize) shouldBe Success(i2)
  }
}

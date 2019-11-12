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

import org.apache.openwhisk.core.entity._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}
import spray.json.DefaultJsonProtocol._
import spray.json.{JsNull, JsString, _}

@RunWith(classOf[JUnitRunner])
class ParameterEncryptionTests extends FlatSpec with ExecHelpers with Matchers {

  val parameters = new Parameters(
    Map(
      new ParameterName("one") -> new ParameterValue(JsString("secret"), false),
      new ParameterName("two") -> new ParameterValue(JsString("secret"), false)))

  behavior of "Parameter"
  it should "handle unecryption json objects" in {
    val originalValue =
      """
        |{
        |"paramName1":{"encryption":null,"init":false,"value":"from-action"},
        |"paramName2":{"encryption":null,"init":false,"value":"from-pack"}
        |}
        |""".stripMargin
    val ps = Parameters.serdes.read(originalValue.parseJson)
    ps.get("paramName1").get.convertTo[String] shouldBe "from-action"
    ps.get("paramName2").get.convertTo[String] shouldBe "from-pack"
  }

  behavior of "Parameter"
  it should "drop encryption payload when no longer encrypted" in {
    val originalValue =
      """
        |{
        |"paramName1":{"encryption":null,"init":false,"value":"from-action"},
        |"paramName2":{"encryption":null,"init":false,"value":"from-action"}
        |}
        |""".stripMargin
    val ps = Parameters.serdes.read(originalValue.parseJson)
    val o = ps.toJsObject
    o.fields.map((tuple: (String, JsValue)) => {
      tuple._2.convertTo[String] shouldBe "from-action"
    })
  }

  behavior of "AesParameterEncryption"
  it should "correctly mark the encrypted parameters after lock" in {
    ParameterEncryption.storageConfig = new ParameterStorageConfig("thisisabadkey!!!")
    val locked = ParameterEncryption.lock(parameters)
    locked.getMap.map(({
      case (_, paramValue) =>
        paramValue.encryption.convertTo[String] shouldBe "aes128"
        paramValue.value.convertTo[String] should not be "secret"
    }))
  }

  it should "correctly decrypted encrypted values" in {
    ParameterEncryption.storageConfig = new ParameterStorageConfig("thisisabadkey!!!")
    val locked = ParameterEncryption.lock(parameters)
    locked.getMap.map(({
      case (_, paramValue) =>
        paramValue.encryption.convertTo[String] shouldBe "aes128"
        paramValue.value.convertTo[String] should not be "secret"
    }))

    val unlocked = ParameterEncryption.unlock(locked)
    unlocked.getMap.map(({
      case (_, paramValue) =>
        paramValue.encryption shouldBe JsNull
        paramValue.value.convertTo[String] shouldBe "secret"
    }))

  }

//  it should "work if with aes256 if policy allows it" in {
//    ParameterEncryption.storageConfig = new ParameterStorageConfig("thiskeyisntbetterbecausetislongr")
//    if (javax.crypto.Cipher.getMaxAllowedKeyLength("AES") < 256) {
//      fail(s"Need higher allowed key length than ${javax.crypto.Cipher.getMaxAllowedKeyLength("AES")}")
//    }
//    val locked = Aes256.lock(parameters)
//    locked.getMap.map(({
//      case (_, paramValue) =>
//        paramValue.encryption.convertTo[String] shouldBe "aes256"
//        paramValue.value.convertTo[String] should not be "secret"
//    }))
//
//  }

  behavior of "NoopEncryption"
  it should "not mark parameters as encrypted" in {
    val unlock = ParameterEncryption.unlock(parameters)
    unlock.getMap.map(({
      case (_, paramValue) =>
        paramValue.encryption shouldBe JsNull
        paramValue.value.convertTo[String] shouldBe "secret"
    }))
  }
}

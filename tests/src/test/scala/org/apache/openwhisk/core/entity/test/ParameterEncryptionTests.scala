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

import java.security.InvalidAlgorithmParameterException

import org.apache.openwhisk.core.entity._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}
import spray.json.DefaultJsonProtocol._
import spray.json._

@RunWith(classOf[JUnitRunner])
class ParameterEncryptionTests extends FlatSpec with Matchers with BeforeAndAfter {

  after {
    ParameterEncryption.storageConfig = new ParameterStorageConfig("")
  }

  val parameters = new Parameters(
    Map(
      new ParameterName("one") -> new ParameterValue("secret".toJson, false),
      new ParameterName("two") -> new ParameterValue("secret".toJson, true)))

  behavior of "Parameters"
  it should "handle complex objects in param body" in {
    val input =
      """
        |{
        |    "__ow_headers": {
        |        "accept": "*/*",
        |        "accept-encoding": "gzip, deflate",
        |        "host": "controllers",
        |        "user-agent": "Apache-HttpClient/4.5.5 (Java/1.8.0_212)",
        |        "x-request-id": "fd2263668266da5a5433109076191d95"
        |    },
        |    "__ow_method": "get",
        |    "__ow_path": "/a",
        |    "a": "A"
        |}
        |""".stripMargin
    val ps = Parameters.readMergedList(input.parseJson)
    ps.get("a").get.convertTo[String] shouldBe "A"
  }

  it should "handle decryption json objects" in {
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

  it should "read the merged unencrypted parameters during mixed storage" in {
    val originalValue =
      """
        |{"name":"from-action","other":"from-action"}
        |""".stripMargin
    val ps = Parameters.readMergedList(originalValue.parseJson)
    val o = ps.toJsObject
    o.fields.map((tuple: (String, JsValue)) => {
      tuple._2.convertTo[String] shouldBe "from-action"
    })
  }

  it should "read the merged message payload from kafka into parameters" in {
    ParameterEncryption.storageConfig = new ParameterStorageConfig("aes-128", "ra1V6AfOYAv0jCzEdufIFA==")
    val locked = ParameterEncryption.lock(parameters)

    val unlockedParam = new ParameterValue(JsString("test-plain"), false)
    val mixedParams =
      locked.merge(Some((new Parameters(Map.empty) + (new ParameterName("plain") -> unlockedParam)).toJsObject))
    val params = Parameters.readMergedList(mixedParams.get)
    params.get("one").get shouldBe locked.get("one").get
    params.get("two").get shouldBe locked.get("two").get
    params.get("two").get should not be locked.get("one").get
    params.get("plain").get shouldBe JsString("test-plain")
  }

  behavior of "AesParameterEncryption"
  it should "correctly mark the encrypted parameters after lock" in {
    ParameterEncryption.storageConfig = new ParameterStorageConfig("aes-128", "ra1V6AfOYAv0jCzEdufIFA==")
    val locked = ParameterEncryption.lock(parameters)
    locked.getMap.map(({
      case (_, paramValue) =>
        paramValue.encryption.convertTo[String] shouldBe "aes-128"
        paramValue.value.convertTo[String] should not be "secret"
    }))
  }

  it should "serialize to json correctly" in {
    val output =
      """\Q{"one":{"encryption":"aes-128","init":false,"value":"\E.*\Q"},"two":{"encryption":"aes-128","init":true,"value":"\E.*\Q"}}""".stripMargin.r
    ParameterEncryption.storageConfig = new ParameterStorageConfig("aes-128", "ra1V6AfOYAv0jCzEdufIFA==")
    val locked = ParameterEncryption.lock(parameters)
    val dbString = locked.toJsObject.toString
    dbString should fullyMatch regex output
  }

  it should "correctly decrypted encrypted values" in {
    ParameterEncryption.storageConfig = new ParameterStorageConfig("aes-128", "ra1V6AfOYAv0jCzEdufIFA==")
    val locked = ParameterEncryption.lock(parameters)
    locked.getMap.map(({
      case (_, paramValue) =>
        paramValue.encryption.convertTo[String] shouldBe "aes-128"
        paramValue.value.convertTo[String] should not be "secret"
    }))

    val unlocked = ParameterEncryption.unlock(locked)
    unlocked.getMap.map(({
      case (_, paramValue) =>
        paramValue.encryption shouldBe JsNull
        paramValue.value.convertTo[String] shouldBe "secret"
    }))
  }
  it should "correctly decrypted encrypted JsObject values" in {
    ParameterEncryption.storageConfig = new ParameterStorageConfig("aes-128", "ra1V6AfOYAv0jCzEdufIFA==")
    val obj = Map("key" -> "xyz".toJson, "value" -> "v1".toJson).toJson

    val complexParam = new Parameters(Map(new ParameterName("one") -> new ParameterValue(obj, false)))

    val locked = ParameterEncryption.lock(complexParam)
    locked.getMap.map(({
      case (_, paramValue) =>
        paramValue.encryption.convertTo[String] shouldBe "aes-128"
        paramValue.value.convertTo[String] should not be "secret"
    }))

    val unlocked = ParameterEncryption.unlock(locked)
    unlocked.getMap.map(({
      case (_, paramValue) =>
        paramValue.encryption shouldBe JsNull
        paramValue.value shouldBe obj
    }))
  }
  it should "correctly decrypted encrypted multiline values" in {
    ParameterEncryption.storageConfig = new ParameterStorageConfig("aes-128", "ra1V6AfOYAv0jCzEdufIFA==")
    val lines = "line1\nline2\nline3\nline4"
    val multiline = new Parameters(Map(new ParameterName("one") -> new ParameterValue(JsString(lines), false)))

    val locked = ParameterEncryption.lock(multiline)
    locked.getMap.map(({
      case (_, paramValue) =>
        paramValue.encryption.convertTo[String] shouldBe "aes-128"
        paramValue.value.convertTo[String] should not be "secret"
    }))

    val unlocked = ParameterEncryption.unlock(locked)
    unlocked.getMap.map(({
      case (_, paramValue) =>
        paramValue.encryption shouldBe JsNull
        paramValue.value.convertTo[String] shouldBe lines
    }))
  }
  // Not sure having cancelled tests is a good idea either, need to work on aes256 packaging.
  it should "work if with aes256 if policy allows it" in {
    ParameterEncryption.storageConfig =
      new ParameterStorageConfig("aes-256", "", "j5rLzhtxwzPyUVUy8/p8XJmBoKeDoSzNJP1SITJEY9E=")
    try {
      val locked = ParameterEncryption.lock(parameters)
      locked.getMap.map(({
        case (_, paramValue) =>
          paramValue.encryption.convertTo[String] shouldBe "aes-256"
          paramValue.value.convertTo[String] should not be "secret"
      }))

      val unlocked = ParameterEncryption.unlock(locked)
      unlocked.getMap.map(({
        case (_, paramValue) =>
          paramValue.encryption shouldBe JsNull
          paramValue.value.convertTo[String] shouldBe "secret"
      }))
    } catch {
      case e: InvalidAlgorithmParameterException =>
        cancel(e.toString)
    }
  }
  it should "support reverting back to Noop encryption" in {
    ParameterEncryption.storageConfig = new ParameterStorageConfig("aes-128", "ra1V6AfOYAv0jCzEdufIFA==", "")
    try {
      val locked = ParameterEncryption.lock(parameters)
      locked.getMap.map(({
        case (_, paramValue) =>
          paramValue.encryption.convertTo[String] shouldBe "aes-128"
          paramValue.value.convertTo[String] should not be "secret"
      }))

      val lockedJson = locked.toJsObject

      ParameterEncryption.storageConfig = new ParameterStorageConfig("", "ra1V6AfOYAv0jCzEdufIFA==", "")

      val toDecrypt = Parameters.serdes.read(lockedJson)

      val unlocked = ParameterEncryption.unlock(toDecrypt)
      unlocked.getMap.map(({
        case (_, paramValue) =>
          paramValue.encryption shouldBe JsNull
          paramValue.value.convertTo[String] shouldBe "secret"
      }))
      unlocked.toJsObject should not be JsNull
    } catch {
      case e: InvalidAlgorithmParameterException =>
        cancel(e.toString)
    }
  }

  behavior of "NoopEncryption"
  it should "not mark parameters as encrypted" in {
    val locked = ParameterEncryption.lock(parameters)
    locked.getMap.map(({
      case (_, paramValue) =>
        paramValue.value.convertTo[String] shouldBe "secret"
    }))
  }
}

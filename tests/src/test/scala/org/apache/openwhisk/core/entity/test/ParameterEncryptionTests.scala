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

  val k128 = "ra1V6AfOYAv0jCzEdufIFA=="
  val k256 = "j5rLzhtxwzPyUVUy8/p8XJmBoKeDoSzNJP1SITJEY9E="

  // default is no-op but keys are available to decode encoded params
  val noop = ParameterEncryption(ParameterStorageConfig(aes128 = Some(k128), aes256 = Some(k256)))

  val aes128decoder = ParameterEncryption(ParameterStorageConfig("aes-128", aes128 = Some(k128)))
  val aes128encoder = aes128decoder.default

  val aes256decoder = ParameterEncryption(ParameterStorageConfig("aes-256", aes256 = Some(k256)))
  val aes256encoder = aes256decoder.default

  val parameters = new Parameters(
    Map(
      new ParameterName("one") -> new ParameterValue("secret".toJson, false),
      new ParameterName("two") -> new ParameterValue("secret".toJson, true)))

  behavior of "ParameterEncryption"

  it should "not have a default coder when turned off" in {
    ParameterEncryption(ParameterStorageConfig("")).default shouldBe empty
    ParameterEncryption(ParameterStorageConfig("off")).default shouldBe empty
    ParameterEncryption(ParameterStorageConfig("noop")).default shouldBe empty
    ParameterEncryption(ParameterStorageConfig("OFF")).default shouldBe empty
    ParameterEncryption(ParameterStorageConfig("NOOP")).default shouldBe empty
  }

  behavior of "Parameters"

  it should "handle decryption of json objects" in {
    val originalValue =
      """
        |[
        |  { "key": "paramName1", "init": false, "value": "from-action" },
        |  { "key": "paramName2", "init": false, "value": "from-pack" }
        |]
        |""".stripMargin

    val p = Parameters.serdes.read(originalValue.parseJson)
    p.get("paramName1").get.convertTo[String] shouldBe "from-action"
    p.get("paramName2").get.convertTo[String] shouldBe "from-pack"
    p.params.foreach {
      case (_, paramValue) =>
        paramValue.encryption shouldBe empty
    }
  }

  it should "handle decryption of json objects with null field" in {
    val originalValue =
      """
        |[
        |  { "key": "paramName1", "encryption":null, "init": false, "value": "from-action" },
        |  { "key": "paramName2", "encryption":null, "init": false, "value": "from-pack" }
        |]
        |""".stripMargin

    val p = Parameters.serdes.read(originalValue.parseJson)
    p.get("paramName1").get.convertTo[String] shouldBe "from-action"
    p.get("paramName2").get.convertTo[String] shouldBe "from-pack"
    p.params.foreach {
      case (_, paramValue) =>
        paramValue.encryption shouldBe empty
    }
  }

  it should "drop encryption propery when no longer encrypted" in {
    val originalValue =
      """
        |[
        |  { "key": "paramName1", "encryption":null, "init": false, "value": "from-action" },
        |  { "key": "paramName2", "encryption":null, "init": false, "value": "from-pack" }
        |]
        |""".stripMargin

    val p = Parameters.serdes.read(originalValue.parseJson)
    Parameters.serdes.write(p).compactPrint should not include "encryption"
    p.params.foreach {
      case (_, paramValue) =>
        paramValue.encryption shouldBe empty
    }
  }

  it should "read the merged message payload from kafka into parameters" in {
    val locked = parameters.lock(aes128encoder)
    val mixedParams = locked.merge(Some(Parameters("plain", "test-plain").toJsObject))
    mixedParams shouldBe defined
    mixedParams.get.fields("one") shouldBe locked.get("one").get
    mixedParams.get.fields("two") shouldBe locked.get("two").get
    mixedParams.get.fields("two") should not be locked.get("one").get
    mixedParams.get.fields("plain") shouldBe JsString("test-plain")
  }

  behavior of "AesParameterEncryption"

  it should "correctly mark the encrypted parameters after lock" in {
    val locked = parameters.lock(aes128encoder)

    locked.params.foreach {
      case (_, paramValue) =>
        paramValue.encryption shouldBe Some("aes-128")
        paramValue.value.convertTo[String] should not be "secret"
    }
  }

  it should "serialize to json correctly" in {
    val locked = parameters.lock(aes128encoder)
    locked.toJsObject.toString should fullyMatch regex """\Q{"one":"\E.*\Q","two":"\E.*\Q"}""".stripMargin.r
    locked.lockedParameters() shouldBe Map("one" -> "aes-128", "two" -> "aes-128")
  }

  it should "serialize to json correctly when a locked parameter is overriden" in {
    val locked = parameters.lock(aes128encoder)
    locked
      .merge(Some(JsObject("one" -> JsString("override"))))
      .get
      .compactPrint should fullyMatch regex """\Q{"one":"override","two":"\E.*\Q"}""".stripMargin.r
    locked.lockedParameters(Set("one")) shouldBe Map("two" -> "aes-128")
  }

  it should "correctly decrypt encrypted values" in {
    val locked = parameters.lock(aes128encoder)

    locked.params.foreach {
      case (_, paramValue) =>
        paramValue.encryption shouldBe Some("aes-128")
        paramValue.value.convertTo[String] should not be "secret"
    }

    val unlocked = locked.unlock(aes128decoder)
    unlocked.params.foreach {
      case (_, paramValue) =>
        paramValue.encryption shouldBe empty
        paramValue.value.convertTo[String] shouldBe "secret"
    }
  }

  it should "correctly decrypt encrypted JsObject values" in {
    val obj = Map("key" -> "xyz".toJson, "value" -> "v1".toJson).toJson
    val complexParam = new Parameters(Map(new ParameterName("one") -> new ParameterValue(obj, false)))

    val locked = complexParam.lock(aes128encoder)
    locked.params.foreach {
      case (_, paramValue) =>
        paramValue.encryption shouldBe Some("aes-128")
        paramValue.value.convertTo[String] should not be "secret"
    }

    val unlocked = locked.unlock(aes128decoder)
    unlocked.params.foreach {
      case (_, paramValue) =>
        paramValue.encryption shouldBe empty
        paramValue.value shouldBe obj
    }
  }
  it should "correctly decrypt encrypted multiline values" in {
    val lines = "line1\nline2\nline3\nline4"
    val multiline = new Parameters(Map(new ParameterName("one") -> new ParameterValue(JsString(lines), false)))

    val locked = multiline.lock(aes128encoder)
    locked.params.foreach {
      case (_, paramValue) =>
        paramValue.encryption shouldBe Some("aes-128")
        paramValue.value.convertTo[String] should not be "secret"
    }

    val unlocked = locked.unlock(aes128decoder)
    unlocked.params.foreach {
      case (_, paramValue) =>
        paramValue.encryption shouldBe empty
        paramValue.value.convertTo[String] shouldBe lines
    }
  }

  // Not sure having cancelled tests is a good idea either, need to work on aes256 packaging.
  it should "work if with aes256 if policy allows it" in {
    try {
      val locked = parameters.lock(aes256encoder)
      locked.params.foreach {
        case (_, paramValue) =>
          paramValue.encryption shouldBe Some("aes-256")
          paramValue.value.convertTo[String] should not be "secret"
      }

      val unlocked = locked.unlock(noop)
      unlocked.params.foreach {
        case (_, paramValue) =>
          paramValue.encryption shouldBe empty
          paramValue.value.convertTo[String] shouldBe "secret"
      }
    } catch {
      case e: InvalidAlgorithmParameterException =>
        cancel(e.toString)
    }
  }

  it should "support reverting back to Noop encryption" in {
    try {
      val locked = parameters.lock(aes128encoder)
      locked.params.foreach {
        case (_, paramValue) =>
          paramValue.encryption shouldBe Some("aes-128")
          paramValue.value.convertTo[String] should not be "secret"
      }

      val lockedJson = Parameters.serdes.write(locked).compactPrint
      val toDecrypt = Parameters.serdes.read(lockedJson.parseJson)

      // defaults to no-op
      val unlocked = toDecrypt.unlock(noop)
      unlocked.params.foreach {
        case (_, paramValue) =>
          paramValue.encryption shouldBe empty
          paramValue.value.convertTo[String] shouldBe "secret"
      }

      unlocked.toJsObject shouldBe JsObject("one" -> "secret".toJson, "two" -> "secret".toJson)
    } catch {
      case e: InvalidAlgorithmParameterException =>
        cancel(e.toString)
    }
  }

  behavior of "No-op Encryption"

  it should "not mark parameters as encrypted" in {
    val locked = parameters.lock()
    locked.params.foreach {
      case (_, paramValue) =>
        paramValue.value.convertTo[String] shouldBe "secret"
    }
  }
}

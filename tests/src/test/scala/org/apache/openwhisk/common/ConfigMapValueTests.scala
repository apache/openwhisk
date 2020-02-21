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

package org.apache.openwhisk.common

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

import com.typesafe.config.ConfigFactory
import org.apache.commons.io.FileUtils
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}
import pureconfig._
import pureconfig.generic.auto._

@RunWith(classOf[JUnitRunner])
class ConfigMapValueTests extends FlatSpec with Matchers {
  behavior of "ConfigMapValue"

  case class ValueTest(template: ConfigMapValue, count: Int)

  it should "read from string" in {
    val config = ConfigFactory.parseString("""
       |whisk {
       |  value-test {
       |    template = "test string"
       |    count = 42
       |  }
       |}""".stripMargin)

    val valueTest = readValueTest(config)
    valueTest.template.value shouldBe "test string"
  }

  it should "read from file reference" in {
    val file = Files.createTempFile("whisk", null).toFile
    FileUtils.write(file, "test string", UTF_8)

    val config = ConfigFactory.parseString(s"""
       |whisk {
       |  value-test {
       |    template = "${file.toURI}"
       |    count = 42
       |  }
       |}""".stripMargin)

    val valueTest = readValueTest(config)
    valueTest.template.value shouldBe "test string"

    file.delete()
  }

  private def readValueTest(config: com.typesafe.config.Config) = {
    loadConfigOrThrow[ValueTest](config.getConfig("whisk.value-test"))
  }
}

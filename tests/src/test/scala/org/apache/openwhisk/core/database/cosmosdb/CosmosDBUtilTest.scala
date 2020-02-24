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

package org.apache.openwhisk.core.database.cosmosdb

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers, OptionValues}
import spray.json._
import org.apache.openwhisk.utils.JsHelpers

@RunWith(classOf[JUnitRunner])
class CosmosDBUtilTest extends FlatSpec with Matchers with OptionValues {

  behavior of "prepare field"

  it should "always have id" in {
    val result = fieldsAsJson()
    val expected = """{"id" : "r['id']"}""".parseJson
    result shouldBe expected
    result.fields("id") shouldBe JsString("r['id']")
  }

  it should "build a json like string" in {
    val result = fieldsAsJson("a")
    val expected = """{ "a" : "r['a']", "id" : "r['id']"}""".parseJson
    result shouldBe expected
    result.fields("a") shouldBe JsString("r['a']")
  }

  it should "support nested fields" in {
    val result = fieldsAsJson("a", "b.c")
    val expected = """{
                     |  "a": "r['a']",
                     |  "b": {
                     |    "c": "r['b']['c']"
                     |  },
                     |  "id": "r['id']"
                     |}""".stripMargin.parseJson
    result shouldBe expected
    JsHelpers.getFieldPath(result, "b", "c").value shouldBe JsString("r['b']['c']")
  }

  private def fieldsAsJson(fields: String*) = toJson(CosmosDBUtil.prepareFieldClause(fields.toSet))

  private def toJson(s: String): JsObject = {
    //Strip of last `As VIEW`
    s.replace(" AS view", "")
      .replaceAll(raw"(r[\w'\[\]]+)", "\"$1\"")
      .parseJson
      .asJsObject
  }

  behavior of "escaping"

  it should "escape /" in {
    CosmosDBUtil.escapeId("foo/bar") shouldBe "foo|bar"
  }

  it should "throw exception when input contains replacement char |" in {
    an[IllegalArgumentException] should be thrownBy CosmosDBUtil.escapeId("foo/bar|baz")
  }

  it should "support unescaping" in {
    val s = "foo/bar"
    CosmosDBUtil.unescapeId(CosmosDBUtil.escapeId(s)) shouldBe s
  }

}

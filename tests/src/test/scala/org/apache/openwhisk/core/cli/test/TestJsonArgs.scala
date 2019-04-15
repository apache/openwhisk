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

import spray.json._

object TestJsonArgs {

  def getInvalidJSONInput =
    Seq(
      "{\"invalid1\": }",
      "{\"invalid2\": bogus}",
      "{\"invalid1\": \"aKey\"",
      "invalid \"string\"",
      "{\"invalid1\": [1, 2, \"invalid\"\"arr\"]}")

  def getJSONFileOutput() =
    JsArray(
      JsObject("key" -> JsString("a key"), "value" -> JsString("a value")),
      JsObject("key" -> JsString("a bool"), "value" -> JsTrue),
      JsObject("key" -> JsString("objKey"), "value" -> JsObject("b" -> JsString("c"))),
      JsObject(
        "key" -> JsString("objKey2"),
        "value" -> JsObject("another object" -> JsObject("some string" -> JsString("1111")))),
      JsObject(
        "key" -> JsString("objKey3"),
        "value" -> JsObject("json object" -> JsObject("some int" -> JsNumber(1111)))),
      JsObject("key" -> JsString("a number arr"), "value" -> JsArray(JsNumber(1), JsNumber(2), JsNumber(3))),
      JsObject("key" -> JsString("a string arr"), "value" -> JsArray(JsString("1"), JsString("2"), JsString("3"))),
      JsObject("key" -> JsString("a bool arr"), "value" -> JsArray(JsTrue, JsFalse, JsTrue)),
      JsObject("key" -> JsString("strThatLooksLikeJSON"), "value" -> JsString("{\"someKey\": \"someValue\"}")))

  def getEscapedJSONTestArgInput() =
    Map(
      "key1" -> JsObject("nonascii" -> JsString("日本語")),
      "key2" -> JsObject("valid" -> JsString("J\\SO\"N")),
      "\"key\"with\\escapes" -> JsObject("valid" -> JsString("JSON")),
      "another\"escape\"" -> JsObject("valid" -> JsString("\\nJ\\rO\\tS\\bN\\f")))

  def getEscapedJSONTestArgOutput() =
    JsArray(
      JsObject("key" -> JsString("key1"), "value" -> JsObject("nonascii" -> JsString("日本語"))),
      JsObject("key" -> JsString("key2"), "value" -> JsObject("valid" -> JsString("J\\SO\"N"))),
      JsObject("key" -> JsString("\"key\"with\\escapes"), "value" -> JsObject("valid" -> JsString("JSON"))),
      JsObject("key" -> JsString("another\"escape\""), "value" -> JsObject("valid" -> JsString("\\nJ\\rO\\tS\\bN\\f"))))

  def getValidJSONTestArgOutput() =
    JsArray(
      JsObject("key" -> JsString("number"), "value" -> JsNumber(8)),
      JsObject("key" -> JsString("bignumber"), "value" -> JsNumber(12345678912.123456789012)),
      JsObject(
        "key" -> JsString("objArr"),
        "value" -> JsArray(
          JsObject("name" -> JsString("someName"), "required" -> JsTrue),
          JsObject("name" -> JsString("events"), "count" -> JsNumber(10)))),
      JsObject("key" -> JsString("strArr"), "value" -> JsArray(JsString("44"), JsString("55"))),
      JsObject("key" -> JsString("string"), "value" -> JsString("This is a string")),
      JsObject("key" -> JsString("numArr"), "value" -> JsArray(JsNumber(44), JsNumber(55))),
      JsObject(
        "key" -> JsString("object"),
        "value" -> JsObject(
          "objString" -> JsString("aString"),
          "objStrNum" -> JsString("123"),
          "objNum" -> JsNumber(300),
          "objBool" -> JsFalse,
          "objNumArr" -> JsArray(JsNumber(1), JsNumber(2)),
          "objStrArr" -> JsArray(JsString("1"), JsString("2")))),
      JsObject("key" -> JsString("strNum"), "value" -> JsString("9")))

  def getValidJSONTestArgInput() =
    Map(
      "string" -> JsString("This is a string"),
      "strNum" -> JsString("9"),
      "number" -> JsNumber(8),
      "bignumber" -> JsNumber(12345678912.123456789012),
      "numArr" -> JsArray(JsNumber(44), JsNumber(55)),
      "strArr" -> JsArray(JsString("44"), JsString("55")),
      "objArr" -> JsArray(
        JsObject("name" -> JsString("someName"), "required" -> JsTrue),
        JsObject("name" -> JsString("events"), "count" -> JsNumber(10))),
      "object" -> JsObject(
        "objString" -> JsString("aString"),
        "objStrNum" -> JsString("123"),
        "objNum" -> JsNumber(300),
        "objBool" -> JsFalse,
        "objNumArr" -> JsArray(JsNumber(1), JsNumber(2)),
        "objStrArr" -> JsArray(JsString("1"), JsString("2"))))
}

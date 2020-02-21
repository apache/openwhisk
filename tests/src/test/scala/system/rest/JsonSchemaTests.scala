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

package system.rest

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

/**
 * Basic tests of API calls for actions
 */
@RunWith(classOf[JUnitRunner])
class JsonSchemaTests extends FlatSpec with Matchers with JsonSchema with RestUtil {

  def TEST_SCHEMA = """{
      "type" : "object",
      "properties" : {
        "price" : {"type" : "number"},
        "name" :  {"type" : "string"}
       }
    }"""

  "JSON schema validator" should "accept a correct object" in {
    def GOOD = """ {"name" : "Eggs", "price" : 34.99} """
    assert(check(GOOD, TEST_SCHEMA))
  }
  it should "reject a bad object" in {
    def BAD = """ {"name" : "Eggs", "price" : "Invalid"} """
    assert(!check(BAD, TEST_SCHEMA))
  }

  it should "accept a properly structured Action" in {
    val schema = getJsonSchema("Action").compactPrint
    def ACTION = """ {"namespace":"_",
                       | "name":"foo",
                       | "version":"1.1.1",
                       | "publish":false,
                       | "exec":{ "code": "foo", "kind": "nodejs:10" },
                       | "parameters":["key1","value1"],
                       | "limits":{ "timeout":1000, "memory":200 } }""".stripMargin
    assert(check(ACTION, schema))
  }

  it should "reject an improperly structured Action" in {
    val schema = getJsonSchema("Action").compactPrint
    def ACTION = """ {"sname" : "foo", "spublish" : false} """
    assert(!check(ACTION, schema))
  }
}

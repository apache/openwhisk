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

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import common.StreamLogging

@RunWith(classOf[JUnitRunner])
class ConfigTests extends FlatSpec with Matchers with StreamLogging {

  "Config" should "gets default value" in {
    val config = new Config(Map("a" -> "A"))(Map.empty)
    assert(config.isValid && config("a") == "A")
  }

  it should "get value from environment" in {
    val config = new Config(Map("a" -> null, "b" -> ""))(Map("A" -> "xyz"))
    assert(config.isValid && config("a") == "xyz" && config("b") == "")
  }

  it should "not be valid when environment does not provide value" in {
    val config = new Config(Map("a" -> null))(Map.empty)
    assert(!config.isValid && config("a") == null)
  }

  it should "be invalid if same property is required and optional and still not defined" in {
    val config = new Config(Map("a" -> null), optionalProperties = Set("a"))(Map.empty)
    assert(!config.isValid)
  }

  it should "read optional value" in {
    val config = new Config(Map("a" -> "A", "x" -> "X"), optionalProperties = Set("b", "c", "x"))(Map("B" -> "B"))
    assert(config.isValid)
    assert(config("a") == "A")
    assert(config("b") == "B")
    assert(config("c") == "")
    assert(config("x") == "X")
  }

  it should "override a value with optional value" in {
    val config =
      new Config(Map("a" -> null, "x" -> "X"), optionalProperties = Set("b", "c", "x"))(Map("A" -> "A", "B" -> "B"))
    assert(config.isValid && config("a") == "A" && config("b") == "B")
    assert(config("a", "b") == "B")
    assert(config("a", "c") == "A")
    assert(config("c") == "")
    assert(config("x") == "X")
    assert(config("x", "c") == "X")
    assert(config("x", "d") == "X")
    assert(config("d", "x") == "X")
    assert(config("c", "x") == "X")
    assert(config("c", "d") == "")
  }

}

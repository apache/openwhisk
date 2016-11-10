/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.common

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ConfigTests extends FlatSpec with Matchers {

    "Config" should "gets default value" in {
        val config = new Config(Map("a" -> "A"))(Map())
        assert(config.isValid && config("a") == "A")
    }

    it should "get value from environemnt" in {
        val config = new Config(Map("a" -> null))(Map("A" -> "xyz"))
        assert(config.isValid && config("a") == "xyz")
    }

    it should "not be valid when environment does not provide value" in {
        val config = new Config(Map("a" -> null))(Map())
        assert(!config.isValid && config("a") == null)
    }

    it should "read optional value" in {
        val config = new Config(Map("a" -> "A"), Set("b", "c"))(Map("B" -> "xyz"))
        assert(config.isValid && config("a") == "A" && config("b") == "xyz" && config("c") == null)
    }

    it should "override a value with optional value" in {
        val config = new Config(Map("a" -> null), optionalProperties = Set("b", "c"))(Map("A" -> "xyz", "B" -> "zyx"))
        assert(config.isValid && config("a") == "xyz" && config("b") == "zyx")
        assert(config("a", "b") == "zyx")
        assert(config("a", "c") == "xyz")
    }
}

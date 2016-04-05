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
import whisk.core.WhiskConfig
import spray.json.JsString

@RunWith(classOf[JUnitRunner])
class CommonTests extends FlatSpec with Matchers {

    "WhiskConfig" should "get required property" in {
        val config = new WhiskConfig(WhiskConfig.edgeHost)
        assert(config.isValid)
        assert(config.edgeHost.nonEmpty)
    }

    it should "get property with no value" in {
        val config = new WhiskConfig(Map(WhiskConfig.dockerRegistry -> null))
        println(s"${WhiskConfig.dockerRegistry} is: '${config.dockerRegistry}'")
        assert(config.isValid)
    }

    it should "read properties from consulserver" in {
        val tester = new WhiskConfig(WhiskConfig.consulServer);
        val consul = new ConsulKV(tester.consulServer)

        val key = "whiskprops/CONSUL_TEST_CASE"
        consul.put(key, JsString("thiswastested"))

        val config = new WhiskConfig(Map("consul.test.case" -> null))

        assert(config.isValid)
        assert(config("consul.test.case").equals("thiswastested"))

        consul.delete(key)
    }
}

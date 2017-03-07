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

package whisk.core

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

import scala.concurrent.Await
import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import common.StreamLogging
import common.WskActorSystem
import whisk.common.ConsulClient

@RunWith(classOf[JUnitRunner])
class WhiskConfigTests
    extends FlatSpec
    with Matchers
    with WskActorSystem
    with StreamLogging {

    behavior of "WhiskConfig"

    it should "get required property" in {
        val config = new WhiskConfig(WhiskConfig.edgeHost)
        assert(config.isValid)
        assert(config.edgeHost.nonEmpty)
    }

    it should "be valid when a prop file is provided defining required props" in {
        val file = File.createTempFile("cxt", ".txt")
        file.deleteOnExit()

        val bw = new BufferedWriter(new FileWriter(file))
        bw.write("a=A\n")
        bw.close()

        val config = new WhiskConfig(Map("a" -> null), Set(), file)
        assert(config.isValid && config("a") == "A")
    }

    it should "not be valid when a prop file is provided but does not define required props" in {
        val file = File.createTempFile("cxt", ".txt")
        file.deleteOnExit()

        val bw = new BufferedWriter(new FileWriter(file))
        bw.write("a=A\n")
        bw.close()

        val config = new WhiskConfig(Map("a" -> null, "b" -> null), Set(), file)
        assert(!config.isValid && config("b") == null)
    }

    it should "be valid when a prop file is provided defining required props and optional properties" in {
        val file = File.createTempFile("cxt", ".txt")
        file.deleteOnExit()

        val bw = new BufferedWriter(new FileWriter(file))
        bw.write("a=A\n")
        bw.write("b=B\n")
        bw.write("c=C\n")
        bw.close()

        val config = new WhiskConfig(Map("a" -> null, "b" -> "???"), Set("c", "d"), file, env = Map())
        assert(config.isValid && config("a") == "A" && config("b") == "B")
        assert(config("c") == "C")
        assert(config("d") == "")
        assert(config("a", "c") == "C")
        assert(config("a", "d") == "A")
        assert(config("d", "a") == "A")
        assert(config("c", "a") == "A")
    }

    it should "get property with no value from whisk.properties file" in {
        val config = new WhiskConfig(Map(WhiskConfig.dockerRegistry -> null))
        println(s"${WhiskConfig.dockerRegistry} is: '${config.dockerRegistry}'")
        assert(config.isValid)
    }

    it should "read properties from consulserver" in {
        val tester = new WhiskConfig(WhiskConfig.consulServer);
        val consul = new ConsulClient(tester.consulServer)

        val key = "whiskprops/CONSUL_TEST_CASE"
        Await.result(consul.kv.put(key, "thiswastested"), 10.seconds)

        // set optional value which will not be available in environment, it should still be read from consul
        val config = new WhiskConfig(WhiskConfig.consulServer ++ Map("consul.test.case" -> null), Set("consul.test.case"))

        assert(config.isValid)
        assert(config("consul.test.case").equals("thiswastested"))

        consul.kv.del(key)
    }

}

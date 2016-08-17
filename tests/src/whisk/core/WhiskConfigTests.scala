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

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class WhiskConfigTests extends FlatSpec with Matchers {

    it should "be valid when a prop file is provided defining required props" in {
        val file = File.createTempFile("cxt", ".txt")
        file.deleteOnExit()

        val bw = new BufferedWriter(new FileWriter(file))
        bw.write("a=A")
        bw.close()

        val config = new WhiskConfig(Map("a" -> null), file)
        assert(config.isValid && config("a") == "A")
    }

    it should "not be valid when a prop file is provided but does not define required props" in {
        val file = File.createTempFile("cxt", ".txt")
        file.deleteOnExit()

        val bw = new BufferedWriter(new FileWriter(file))
        bw.write("a=A")
        bw.close()

        val config = new WhiskConfig(Map("a" -> null, "b" -> null), file)
        assert(!config.isValid && config("b") == null)
    }
}

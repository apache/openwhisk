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

package org.apache.openwhisk.core

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import common.StreamLogging

@RunWith(classOf[JUnitRunner])
class WhiskConfigTests extends FlatSpec with Matchers with StreamLogging {

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

    val config = new WhiskConfig(Map("a" -> null), Set.empty, file)
    assert(config.isValid && config("a") == "A")
  }

  it should "not be valid when a prop file is provided but does not define required props" in {
    val file = File.createTempFile("cxt", ".txt")
    file.deleteOnExit()

    val bw = new BufferedWriter(new FileWriter(file))
    bw.write("a=A\n")
    bw.close()

    val config = new WhiskConfig(Map("a" -> null, "b" -> null), Set.empty, file)
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

    val config = new WhiskConfig(Map("a" -> null, "b" -> "???"), Set("c", "d"), file, env = Map.empty)
    assert(config.isValid && config("a") == "A" && config("b") == "B")
    assert(config("c") == "C")
    assert(config("d") == "")
    assert(config("a", "c") == "C")
    assert(config("a", "d") == "A")
    assert(config("d", "a") == "A")
    assert(config("c", "a") == "A")
  }
}

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

package org.apache.openwhisk.spi

import com.typesafe.config.ConfigException
import common.StreamLogging
import common.WskActorSystem
import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class SpiTests extends FlatSpec with Matchers with WskActorSystem with StreamLogging {

  behavior of "SpiProvider"

  it should "load an Spi from SpiLoader via typesafe config" in {
    val simpleSpi = SpiLoader.get[SimpleSpi]
    simpleSpi shouldBe a[SimpleSpi]
  }

  it should "throw an exception if the impl defined in application.conf is missing" in {
    a[ClassNotFoundException] should be thrownBy SpiLoader.get[MissingSpi]
  }

  it should "throw an exception if the module is missing" in {
    a[ClassNotFoundException] should be thrownBy SpiLoader.get[MissingModule]
  }

  it should "throw an exception if the config key is missing" in {
    a[ConfigException] should be thrownBy SpiLoader.get[MissingKey]
  }
}

trait SimpleSpi extends Spi
object SimpleSpiImpl extends SimpleSpi

trait MissingSpi extends Spi
trait MissingModule extends Spi
trait MissingKey extends Spi

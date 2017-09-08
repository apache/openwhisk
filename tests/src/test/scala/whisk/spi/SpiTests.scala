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

package whisk.spi

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
  it should "create a single cached instance when accessing a caching SPI with same key" in {
    val first = SpiLoader.get[CachedValueProvider[String]].getOrInit("key", "value1")
    val second = SpiLoader.get[CachedValueProvider[String]].getOrInit("key", "value2")
    first shouldBe "value1"
    first shouldBe second
  }
  it should "create a separate instance when accessing a caching Spi with different keys" in {
    val first = SpiLoader.get[CachedValueProvider[String]].getOrInit("key", "value1")
    val second = SpiLoader.get[CachedValueProvider[String]].getOrInit("key2", "value2")
    first should not be second
    first shouldBe "value1"
    second shouldBe "value2"
  }
  it should "recreate a cached instance when accessing a caching SPI with same key after removal" in {
    val loader = SpiLoader.get[CachedValueProvider[String]]
    val first = loader.getOrInit("key", "value1")
    val second = loader.getOrInit("key", "value2")
    first shouldBe "value1"
    second shouldBe "value1"
    //now remove
    loader.cleanup("key")
    val third = loader.getOrInit("key", "value2")
    third shouldBe "value2"
  }
}

trait SimpleSpi extends Spi
object SimpleSpiImpl extends SimpleSpi

trait CachedValueProvider[D] extends Spi {
  def getOrInit(key: String, value: D): D
  def cleanup(key: String): Option[D]
}
object CachedValueProviderImpl extends CachedValueProvider[String] with SpiInstanceCaching[String, String] {
  override def getOrInit(key: String, value: String): String = getInstanceOrCreate(key, value)
  override def cleanup(key: String) = removeInstance(key)
}

trait MissingSpi extends Spi
trait MissingModule extends Spi
trait MissingKey extends Spi

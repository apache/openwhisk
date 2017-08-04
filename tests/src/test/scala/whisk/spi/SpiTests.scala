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
import whisk.core.WhiskConfig

@RunWith(classOf[JUnitRunner])
class SpiTests extends FlatSpec with Matchers with WskActorSystem with StreamLogging {

    behavior of "SpiProvider"

    it should "load an Spi from SpiLoader via typesafe config" in {
        val simpleSpi = SpiLoader.get[SimpleSpi]()
        simpleSpi shouldBe a[SimpleSpi]
    }

    it should "throw an exception if the impl defined in application.conf is missing" in {
        a[ClassNotFoundException] should be thrownBy SpiLoader.get[MissingSpi]() // MissingSpi(actorSystem)
    }

    it should "throw an exception if the module is missing" in {
        a[ClassNotFoundException] should be thrownBy SpiLoader.get[MissingModule]() // MissingModule(actorSystem)
    }

    it should "throw an exception if the config key is missing" in {
        a[ConfigException] should be thrownBy SpiLoader.get[MissingKey]() // MissingModule(actorSystem)
    }

    it should "load an Spi with injected WhiskConfig" in {
        val whiskConfig = new WhiskConfig(Map())
        val deps = Dependencies("some name", whiskConfig)
        val dependentSpi = SpiLoader.get[DependentSpi](deps)
        dependentSpi.config shouldBe whiskConfig
    }

    it should "load an Spi with injected Spi" in {
        val whiskConfig = new WhiskConfig(Map())
        val deps = Dependencies("some name", whiskConfig)
        val dependentSpi = SpiLoader.get[DependentSpi](deps)

        val deps2 = Dependencies("dep2", dependentSpi)
        val testSpi = SpiLoader.get[TestSpi](deps2)

        testSpi.dep shouldBe dependentSpi
    }

    it should "not allow duplicate-type dependencies" in {
        a[IllegalArgumentException] should be thrownBy Dependencies("some string", "some other string")
    }

    it should "load SPI impls as singletons via SingletonSpiFactory" in {
        val instance1 = SpiLoader.get[DependentSpi]()
        val instance2 = SpiLoader.get[DependentSpi]()
        val instance3 = SpiLoader.get[DependentSpi]()

        instance1 shouldBe instance2
        instance2 shouldBe instance3
    }

    it should "load SPI impls as singletons via lazy val init" in {
        val instance1 = SpiLoader.get[SimpleSpi]()
        val instance2 = SpiLoader.get[SimpleSpi]()
        val instance3 = SpiLoader.get[SimpleSpi]()

        instance1 shouldBe instance2
        instance2 shouldBe instance3
    }
}

trait TestSpi extends Spi {
    val name: String
    val dep: DependentSpi
}

trait DependentSpi extends Spi {
    val name: String
    val config: WhiskConfig
}

trait TestSpiFactory extends Spi {
    def getTestSpi(name: String, dep: DependentSpi): TestSpi
}

trait DependentSpiFactory extends Spi {
    def getDependentSpi(name: String, config: WhiskConfig): DependentSpi
}

abstract class Key(key: String) {

}

trait SimpleSpi extends Spi {
    val name: String
}

trait MissingSpi extends Spi {
    val name: String
}

trait MissingModule extends Spi {
    val name: String
}
trait MissingKey extends Spi

//SPI impls
//a singleton enforced by SingletonSpiFactory
class DepSpiImpl(val name: String, val config: WhiskConfig) extends DependentSpi
object DepSpiImpl extends SingletonSpiFactory[DependentSpi] {
    override def apply(deps: Dependencies): DependentSpi = {
        new DepSpiImpl(deps.get[String], deps.get[WhiskConfig])
    }
}

class TestSpiImpl(val name: String, val dep: DependentSpi) extends TestSpi
//an alternative to extending SingletonSpiFactory is using lazy val:
object TestSpiImpl extends SpiFactory[TestSpi] {
    var name: String = null
    var conf: DependentSpi = null
    lazy val instance = new TestSpiImpl(name, conf)
    override def apply(dependencies: Dependencies): TestSpi = {
        name = dependencies.get[String]
        conf = dependencies.get[DependentSpi]
        instance
    }

}

class TestSpiFactoryImpl extends TestSpiFactory {
    def getTestSpi(name: String, dep: DependentSpi) = new TestSpiImpl(name, dep)
}

object TestSpiFactoryImpl extends SpiFactory[TestSpiFactory] {
    override def apply(deps: Dependencies): TestSpiFactory = new TestSpiFactoryImpl()
}

class DependentSpiFactoryImpl extends DependentSpiFactory {
    override def getDependentSpi(name: String, config: WhiskConfig): DependentSpi = new DepSpiImpl(name, config)
}

object DependentSpiFactoryImpl extends SpiFactory[DependentSpiFactory] {
    override def apply(deps: Dependencies): DependentSpiFactory = new DependentSpiFactoryImpl()
}

class SimpleSpiImpl(val name: String) extends SimpleSpi

object SimpleSpiImpl extends SingletonSpiFactory[SimpleSpi] {
    override def apply(dependencies: Dependencies): SimpleSpi = new SimpleSpiImpl("some val ")
}

class MissingSpiImpl(val name: String) extends MissingSpi

object MissingSpiImpl extends SpiFactory[MissingSpi] {
    override def apply(deps: Dependencies): MissingSpi = new MissingSpiImpl("some val ")
}

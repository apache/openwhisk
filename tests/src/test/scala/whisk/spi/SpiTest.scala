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
class SpiTest extends FlatSpec with Matchers with WskActorSystem with StreamLogging {

    implicit val resolver = new TypesafeConfigClassResolver(actorSystem.settings.config)

    behavior of "SpiProvider"

    it should "load an Spi from SpiLoader via classname" in {
        val simpleSpi: SimpleSpi = SpiLoader.instanceOf[SimpleSpi]("test.simplespi.impl")
        assert(simpleSpi != null)
    }
    it should "load an Spi from SpiLoader via typesafe config" in {
        val simpleSpi: SimpleSpi = SpiLoader.instanceOf[SimpleSpi]("test.simplespi.impl")
        assert(simpleSpi != null)
    }

    it should "throw an exception if the impl defined in application.conf is missing" in {
        a[ClassNotFoundException] should be thrownBy SpiLoader.instanceOf[MissingSpi]("test.missingspi.impl") // MissingSpi(actorSystem)
    }
    it should "throw an exception if the module is missing" in {
        a[ClassNotFoundException] should be thrownBy SpiLoader.instanceOf[MissingModule]("test.missingmodulespi.impl") // MissingModule(actorSystem)
    }
    it should "throw an exception if the config key is missing" in {
        a[ConfigException] should be thrownBy SpiLoader.instanceOf[MissingModule]("test.missing.config") // MissingModule(actorSystem)
    }
    //
    it should "load an Spi with injected WhiskConfig" in {
        val whiskConfig = new WhiskConfig(Map())
        val deps = Dependencies("some name", whiskConfig)
        val dependentSpi: DependentSpi = SpiLoader.instanceOf[DependentSpi]("test.depspi.impl", deps)
        assert(dependentSpi.config.eq(whiskConfig))
    }
    //
    it should "load an Spi with injected Spi" in {
        val whiskConfig = new WhiskConfig(Map())
        val deps = Dependencies("some name", whiskConfig)
        val dependentSpi: DependentSpi = SpiLoader.instanceOf[DependentSpi]("test.depspi.impl", deps)

        val deps2 = Dependencies("dep2", dependentSpi)
        val testSpi: TestSpi = SpiLoader.instanceOf[TestSpi]("test.testspi.impl", deps2)
        assert(testSpi.dep.eq(dependentSpi))
    }
    //
    it should "not allow duplicate-type dependencies" in {
        a[IllegalArgumentException] should be thrownBy  Dependencies("some string", "some other string")
    }
    //
    it should "load SPI impls as singletons via SingletonSpiFactory" in {
        val instance1 = SpiLoader.instanceOf[DependentSpi]("test.depspi.impl")
        val instance2 = SpiLoader.instanceOf[DependentSpi]("test.depspi.impl")
        val instance3 = SpiLoader.instanceOf[DependentSpi]("test.depspi.impl")
        assert(instance1.hashCode() == instance2.hashCode())

    }

    //
    it should "load SPI impls as singletons via lazy val init" in {
        val instance1 = SpiLoader.instanceOf[SimpleSpi]("test.simplespi.impl")
        val instance2 = SpiLoader.instanceOf[SimpleSpi]("test.simplespi.impl")
        val instance3 = SpiLoader.instanceOf[SimpleSpi]("test.simplespi.impl")
        assert(instance1.hashCode() == instance2.hashCode())

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
    def getTestSpi(name: String, dep: DependentSpi):TestSpi
}

trait DependentSpiFactory extends Spi {
    def getDependentSpi(name: String, config: WhiskConfig):DependentSpi
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


//SPI impls
//a singleton enforced by SingletonSpiFactory
class DepSpiImpl(val name: String, val config: WhiskConfig) extends DependentSpi
object DepSpiImpl extends SingletonSpiFactory[DependentSpi] {
    override def buildInstance(deps:Dependencies): DependentSpi = {
        new DepSpiImpl(deps.get[String], deps.get[WhiskConfig])
    }
}

class TestSpiImpl(val name: String, val dep: DependentSpi) extends TestSpi
//an alternative to extending SingletonSpiFactory is using lazy val:
object TestSpiImpl extends SpiFactory[TestSpi] {
    var name:String = null
    var conf:DependentSpi = null
    lazy val instance =  new TestSpiImpl(name, conf)
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
    override def apply(deps:Dependencies): TestSpiFactory = new TestSpiFactoryImpl()
}

class DependentSpiFactoryImpl extends DependentSpiFactory {
    override def getDependentSpi(name: String, config: WhiskConfig): DependentSpi = new DepSpiImpl(name, config)
}

object DependentSpiFactoryImpl extends SpiFactory[DependentSpiFactory] {
    override def apply(deps:Dependencies): DependentSpiFactory = new DependentSpiFactoryImpl()
}

class SimpleSpiImpl(val name: String) extends SimpleSpi

object SimpleSpiImpl extends SingletonSpiFactory[SimpleSpi] {
    override def buildInstance(dependencies: Dependencies): SimpleSpi = new SimpleSpiImpl("some val ")
}

class MissingSpiImpl(val name: String) extends MissingSpi

object MissingSpiImpl extends SpiFactory[MissingSpi] {
    override def apply(deps:Dependencies): MissingSpi = new MissingSpiImpl("some val ")
}

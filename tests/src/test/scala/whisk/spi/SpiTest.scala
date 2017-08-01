package whisk.spi

import common.StreamLogging
import common.WskActorSystem
import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import whisk.core.WhiskConfig

@RunWith(classOf[JUnitRunner])
class SpiTest extends FlatSpec with Matchers with WskActorSystem with StreamLogging  {

  behavior of "SpiProvider"

  it should "load an Spi from SpiModule implementation" in {
    val simpleSpi:SimpleSpi = SimpleSpi(actorSystem)
    assert(simpleSpi != null)
  }
  it should "throw an exception if the impl defined in application.conf is missing" in {
    a[IllegalArgumentException] should be thrownBy MissingSpi(actorSystem)
  }
  it should "throw an exception if the module is missing" in {
    a[IllegalArgumentException] should be thrownBy MissingModule(actorSystem)
  }


  it should "load an Spi with injected WhiskConfig" in {
    val whiskConfig = new WhiskConfig(Map())
//    SharedModules.bind[WhiskConfig](whiskConfig)
//    val dependentSpi:DependentSpi = DependentSpi2(actorSystem)
    val dependentSpi:DependentSpi = DependentSpi2Factory(actorSystem).getDependentSpi("some dep", whiskConfig)
    assert(dependentSpi.config.eq(whiskConfig))
  }

  it should "load an Spi with injected Spi" in {
    val whiskConfig = new WhiskConfig(Map())
//    SharedModules.bind[WhiskConfig](whiskConfig)
//    val dependentSpi:DependentSpi = DependentSpi2(actorSystem)
    val dependentSpi:DependentSpi = DependentSpi2Factory(actorSystem).getDependentSpi("some dep", whiskConfig)
    val testSpi:TestSpi = TestSpiFactory(actorSystem).getTestSpi("some name", dependentSpi)
    assert(testSpi.dep.eq(dependentSpi))
  }
}


trait TestSpi {
  val name:String
  val dep:DependentSpi
}
trait DependentSpi {
  val name:String
  val config:WhiskConfig
}
trait TestSpiFactory extends Spi {
  def getTestSpi(name:String, dep:DependentSpi) = new TestSpiImpl(name, dep)
}
trait DependentSpiFactory extends Spi {
  def getDependentSpi(name:String, config:WhiskConfig) = new DepSpiImpl(name, config)
}

trait SimpleSpi extends Spi {
  val name:String
}
trait MissingSpi extends Spi {
  val name:String
}
trait MissingModule extends Spi {
  val name:String
}

//object TestSpi extends SpiProvider[TestSpi]("test.testspi.impl")
//object DependentSpi extends SpiProvider[DependentSpi]("test.depspi.impl")
//object DependentSpi2 extends SpiProvider[DependentSpi]("test.depspi.impl")//register a second extension so that the failed first one doesn't impact second text

object TestSpiFactory extends SpiProvider[TestSpiFactory]("test.testspifactory.impl")
object DependentSpiFactory extends SpiProvider[DependentSpiFactory]("test.depspifactory.impl")
object DependentSpi2Factory extends SpiProvider[DependentSpiFactory]("test.depspifactory.impl")//register a second extension so that the failed first one doesn't impact second text
object SimpleSpi extends SpiProvider[SimpleSpi]("test.simplespi.impl")
object MissingSpi extends SpiProvider[MissingSpi]("test.missingspi.impl")
object MissingModule extends SpiProvider[MissingModule]("test.missingmodulespi.impl")


class TestSpiImpl(val name: String, val dep: DependentSpi) extends TestSpi
class DepSpiImpl(val name: String, val config:WhiskConfig) extends DependentSpi
class TestSpiFactoryImpl extends TestSpiFactory
class DependentSpiFactoryImpl extends DependentSpiFactory
class SimpleSpiImpl(val name:String) extends SimpleSpi
class MissingSpiImpl(val name:String) extends MissingSpi//this is missing from application.conf

//class TestSpiProviderModule extends SpiFactoryModule[TestSpi]{
//  def getInstance(implicit injector: Injector): TestSpi = {
//    new TestSpiImpl("this is a test", inject[DependentSpi])
//  }
//}
//class DepSpiProviderModule extends SpiFactoryModule[DependentSpi]{
//  def getInstance(implicit injector:Injector): DependentSpi = {
//    new DepSpiImpl("this is the dependency", inject[WhiskConfig])
//  }
//}

class TestSpiFactoryProviderModule extends SpiModule[TestSpiFactory]{
  def getInstance(): TestSpiFactory = {
    new TestSpiFactoryImpl()
  }
}
class DepSpiFactoryProviderModule extends SpiModule[DependentSpiFactory]{
  def getInstance(): DependentSpiFactory = {
    new DependentSpiFactoryImpl()
  }
}
class SimpleSpiProviderModule extends SpiModule[SimpleSpi]{
  def getInstance():SimpleSpi = {
    new SimpleSpiImpl("this impl has no dependencies")
  }
}
class MissingSpiProviderModule extends SpiModule[MissingSpi]{
  def getInstance():MissingSpi = {
    new MissingSpiImpl("missing")
  }
}
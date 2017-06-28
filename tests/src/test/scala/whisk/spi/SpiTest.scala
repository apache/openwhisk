package whisk.spi

import common.StreamLogging
import common.WskActorSystem
import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import scaldi.InjectException
import scaldi.Injector
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
  it should "throw an exception if the config binding is missing" in {
    a[InjectException] should be thrownBy DependentSpi(actorSystem)
  }

  it should "load an Spi with injected WhiskConfig" in {
    val whiskConfig = new WhiskConfig(Map())
    SharedModules.bind[WhiskConfig](whiskConfig)
    val dependentSpi:DependentSpi = DependentSpi2(actorSystem)
    assert(dependentSpi.config.eq(whiskConfig))
  }

  it should "load an Spi with injected Spi" in {
    val whiskConfig = new WhiskConfig(Map())
    SharedModules.bind[WhiskConfig](whiskConfig)
    val dependentSpi:DependentSpi = DependentSpi2(actorSystem)
    SharedModules.bind(dependentSpi)
    val testSpi:TestSpi = TestSpi(actorSystem)
    assert(testSpi.dep.eq(dependentSpi))
  }
}


trait TestSpi extends Spi {
  val name:String
  val dep:DependentSpi
}
trait DependentSpi extends Spi {
  val name:String
  val config:WhiskConfig
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

object TestSpi extends SpiProvider[TestSpi]("test.testspi.impl")
object DependentSpi extends SpiProvider[DependentSpi]("test.depspi.impl")
object DependentSpi2 extends SpiProvider[DependentSpi]("test.depspi.impl")//register a second extension so that the failed first one doesn't impact second text
object SimpleSpi extends SpiProvider[SimpleSpi]("test.simplespi.impl")
object MissingSpi extends SpiProvider[MissingSpi]("test.missingspi.impl")
object MissingModule extends SpiProvider[MissingModule]("test.missingmodulespi.impl")


class TestSpiImpl(val name: String, val dep: DependentSpi) extends TestSpi
class DepSpiImpl(val name: String, val config:WhiskConfig) extends DependentSpi
class SimpleSpiImpl(val name:String) extends SimpleSpi
class MissingSpiImpl(val name:String) extends MissingSpi//this is missing from application.conf

class TestSpiProviderModule extends SpiFactoryModule[TestSpi]{
  def getInstance(implicit injector: Injector): TestSpi = {
    new TestSpiImpl("this is a test", inject[DependentSpi])
  }
}
class DepSpiProviderModule extends SpiFactoryModule[DependentSpi]{
  def getInstance(implicit injector:Injector): DependentSpi = {
    new DepSpiImpl("this is the dependency", inject[WhiskConfig])
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
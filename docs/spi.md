# SPI extensions in OpenWhisk

Alternate implementations of various components follow an SPI (Service Provider Interface) pattern:
* The pluggable component is defined as an Spi trait:
```scala
import whisk.spi.Spi
trait ThisIsPluggable extends Spi { ... }
```
* Implementations implement the Spi trait
```scala
class TheImpl extends ThisIsPluggable { ... }
class TheOtherImpl extends ThisIsPluggable { ... }
```

Runtime resolution of an Spi trait to a specific impl is provided by:
* Akka Extensions - a service registry housed inside the ActorSystem
* SpiModule - a way to define a factory for each impl, all of which are loaded via ServiceLoader
* application.conf - each SpiModule defines a config key used to indicate which impl should be used at runtime

# Example

The process to create and use an SPI is as follows:

## Define the Spi and impl(s)

* create your Spi trait `YourSpi` as an extension of whisk.spi.Spi
* create your impls as classes that extend `YourSpi`

## Define the SpiModule(s) to load the impl(s)

```scala
class YourImplModule extends SpiModule[YourSpi]{
  def getInstance = new YourImpl
}
```

To use scaldi injectors to bind dependencies, use:
```scala
class YourImplModule extends SpiFactoryModule[YourSpi]{
  def getInstance(implicit injector:Injector) = new YourImpl(inject[SomeDependencyType])
}
```

Note: To bind dependencies (must be done before first access of the Spi):
```scala
//bind config
SharedModules.bind[WhiskConfig](whiskConfig)
//bind an Spi instance
SharedModules.bind[YourSpi](yourSpi)

```



`SpiModule`s are loaded via ServiceLoader at runtime, so you need to add the module to the list at
`META-INF/services/whisk.spi.SpiModule` :
```text
com.otherpackage.OtherImplModule
com.yourpackage.YourImplModule
```

## Define the SpiProvider to configure which impl is used

The configKey indicates which config (in application.conf) is used to determine which impl is used at runtime.
```scala
object YourSpi  extends SpiProvider[YourSpi](configKey = "whisk.spi.yourspi.impl")
```

## Load and use the Spi

In the application, to use the Spi:
```scala

//setup any injectable dependencies (if your Spi impl requires it):
val config = WhiskConfig(Map())
SharedModules.bind[WhiskConfig](config)
//access your Spi using Akka Extension system:
val yourSpi = YourSpi(actorSystem)

```


# Runtime

Since modules are loaded from the classpath, and a specific impl is used only if explicitly configured it is possible to optimize the classpath based on your preference of:
* include only default impls, and only use default impls
* include all impls, and only use the specified impls
* include some combination of defaults and alternate impls, and use the specified impls for the alternates, and default impls for the rest


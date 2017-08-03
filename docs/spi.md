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
* SpiLoader - a utility for loading the impl of a specific Spi, using a resolver to determine the impls factory classname, and reflection to load the factory object
* SpiFactory - a way to define a factory for each impl, all of which are loaded via reflection
* application.conf - each SpiFactory is resolved to a classname based on the config key provided to SpiLoader

A single SpiFactory per unique Spi is usable at runtime, since the key will have a single string value. 

# Example

The process to create and use an SPI is as follows:

## Define the Spi and impl(s)

* create your Spi trait `YourSpi` as an extension of whisk.spi.Spi
* create your impls as classes that extend `YourSpi`

## Define the SpiFactory to load the impl

```scala
class YourImplFactory extends SpiFactory[YourSpi]{
  def apply(dependencies: Dependencies): { ...construct the impl...}
}
```
for singleton behavior you can use
```scala
class YourImplFactory extends SingletonSpiFactory[YourSpi]{
  def buildInstance(dependencies: Dependencies): { ...construct the impl...}
}
```

## Invoke SpiLoader.instanceOf to acquire an instance of the SPI

SpiLoader accepts a key to use for resolving which impl should be loaded. The mapping of key to classname is implemented in an instance of `whisk.spi.SpiClassResolver`
Example whisk.spi.SpiClassResolver impls are:
* whisk.spi.ClassnameResolver - classname is not interpreted by used literally from the value of the key
* whisk.spi.TypesafeConfigClassResolver - classname is looked up as a String from the provided instance of com.typesafe.config.Config (available via `ActorSystem.settings.config` within an Akka app)

Invoke the loader using `SpiLoader.instanceOf[<the SPI interface>](<key to resolve the classname>)(<implicit resolver>)`

The configKey indicates which config (in application.conf) is used to determine which impl is used at runtime.
```scala
val messagingProvider = SpiLoader.instanceOf[MessagingProvider]("whisk.spi.messaging.impl")
```

## Defaults

Default impls resolution is dependent on the `SpiClassResolver` used. 

`TypesafeConfigClassResolver` within an Akka application will load config keys in order of priority from:
1. application.conf
2. reference.conf

So use `reference.conf` to specify defaults with the `TypesafeConfigClassResolver` is used.

# Runtime

Since SPI impls are loaded from the classpath, and a specific impl is used only if explicitly configured it is possible to optimize the classpath based on your preference of:
* include only default impls, and only use default impls
* include all impls, and only use the specified impls
* include some combination of defaults and alternate impls, and use the specified impls for the alternates, and default impls for the rest


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

* create your Spi trait `YourSpi` as an class that is an extension of `whisk.spi.Spi`
* create you SpiFactory impl `YourSpiFactory` as an object that is an extension of `whisk.spi.SpiFactory` (or `whisk.spi.SingletonSpiFactory`)
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
  def apply(dependencies: Dependencies): { ...construct the impl...}
}
```

## Invoke SpiLoader.get to acquire an instance of the SPI

SpiLoader uses a TypesafeConfig key to use for resolving which impl should be loaded. 

The config key used to find the impl classname is `whisk.spi.<SpiInterface>` 

For example, the SPI interface `whisk.core.database.ArtifactStoreProvider` would load a specific impl indicated by the  `whisk.spi.ArtifactStoreProvider` config key.

(so you cannot use multiple SPI interfaces with the same class name in different packages)
 

Invoke the loader using `SpiLoader.get[<the SPI interface>]()(<implicit resolver>)`

```scala
val messagingProvider = SpiLoader.get[MessagingProvider]()
```

## Defaults

Default impls resolution is dependent on the config values in order of priority from:
1. application.conf
2. reference.conf

So use `reference.conf` to specify defaults.

# Runtime

Since SPI impls are loaded from the classpath, and a specific impl is used only if explicitly configured it is possible to optimize the classpath based on your preference of:
* include only default impls, and only use default impls
* include all impls, and only use the specified impls
* include some combination of defaults and alternate impls, and use the specified impls for the alternates, and default impls for the rest


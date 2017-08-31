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

Runtime resolution of an Spi trait to a specific implementation is provided by:
* `SpiLoader` - a utility for loading the implementation of a specific Spi, using a resolver to determine the implentations factory classname, and reflection to load the factory object.
* `application.conf` - each `Spi` is resolved to a classname based on the config key provided to `SpiLoader`.

Only a single implementation per Spi is usable at runtime, since the key will have a single string value.

# Example

The process to create and use an SPI is as follows:

## Define the Spi and implementations

* Create your Spi trait `YourSpi` as a trait that is an extension of `whisk.spi.Spi`.
* Create your factory object which extends `YourSpi` and provides the relevant functionality.
* Create your functionality classes with whatever name you like. The factory object is supposed to build and return those instances.

## Invoke SpiLoader.get to acquire an instance of the SPI

SpiLoader uses a TypesafeConfig key to use for resolving which implementation should be loaded.

The config key used to find the implementations classname is `whisk.spi.<SpiInterface>`.

For example, the SPI interface `whisk.core.database.ArtifactStoreProvider` would load a specific implementation indicated by the  `whisk.spi.ArtifactStoreProvider` config key.

(so you cannot use multiple SPI interfaces with the same class name in different packages)
 

Invoke the loader using `SpiLoader.get[<the SPI interface>](<implicit resolver>)`

```scala
val messagingProvider = SpiLoader.get[MessagingProvider]
```

## Defaults

Default implementation resolution is dependent on the config values in order of priority from:

1. `application.conf`
2. `reference.conf`

So use `reference.conf` to specify defaults.

# Runtime

Since SPI implementations are loaded from the classpath, and a specific implementation is used only if explicitly configured it is possible to optimize the classpath based on your preference of:

* Include only default implementations, and only use default implementations.
* Include all implementations, and only use the specified implementations.
* Include some combination of defaults and alternative implementations, and use the specified implementations for the alternatives, and default implementations for the rest.


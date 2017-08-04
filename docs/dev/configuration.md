# Scala component configuration

Our Scala components are in the process of switching from our own `WhiskConfig` to the TypesafeConfig based `pureconfig`. That will give us a more sane way of parsing environment values, overriding them and defining sensible defaults for them.

TypesafeConfig is able to override its values via JVM system properties. Unfortunately, docker only supports passing environment variables in a comfortable way.

To solve that mismatch, we use a script to transform environment variables into JVM system properties to spare the developer of manually overriding all the values via explicit environment -> TypesafeConfig value mapping. That script applies the following transformations to all environment variables starting with a defined prefix ("CONFIG_" in our case):

- `_` becomes `.` (TypesafeConfig hierarchies are built using `.`)
- `camelCased` becomes `camel-cased` (Kebabcase is usually used for TypesafeConfig keys)
- `PascalCased` stays `PascalCased` (Defining classnames for overriding SPIs is crucial)

### Examples:

- `CONFIG_whisk_loadbalancer_invokerBusyThreshold` becomes `-Dwhisk.loadbalancer.invoker-busy-threshold`
- `CONFIG_akka_remote_netty_tcp_bindPort` becomes `-Dakka.remote.netty.tcp.bind-port`
- `CONFIG_whisk_spi_LogStoreProvider` becomes `-Dwhisk.spi.LogStoreProvider`

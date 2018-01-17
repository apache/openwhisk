# Logging in OpenWhisk

## Default Logging Provider

OpenWhisk uses Logback as default logging provider via slf4j.

Logback can be configured in the configuration file [logback.xml](../common/scala/src/main/resources/logback.xml).

Besides other things this configuration file defines the default log level for OpenWhisk. 
Akka Logging inherits the log level defined here.

## Enable debug-level logging for selected namespaces

For performance reasons it is recommended to leave the default log level on INFO level. For debugging purposes one can enable DEBUG level logging for selected namespaces
(aka special users). A list of namespace ids can be configured using the `nginx_special_users` property in the `group_vars/all` file of an environment.

## Using JMX to adapt the loglevel at run-time

One can alter the log level of a single component (Controller or Invoker) on the fly using JMX. 

### Example for using [jmxterm](ttp://wiki.cyclopsgroup.org/jmxterm/) to alter the log level 

1. Create a command file for jmxterm
```
open <targethost>:<jmx port> -u <jmx username> -p <jmx password>
run -b ch.qos.logback.classic:Name=default,Type=ch.qos.logback.classic.jmx.JMXConfigurator getLoggerLevel ROOT
run -b ch.qos.logback.classic:Name=default,Type=ch.qos.logback.classic.jmx.JMXConfigurator setLoggerLevel ROOT DEBUG
run -b ch.qos.logback.classic:Name=default,Type=ch.qos.logback.classic.jmx.JMXConfigurator getLoggerLevel ROOT
close
```

2. Issue the command with the created file like this:
```
java -jar jmxterm-1.0.0-uber.jar -n silent < filename
```







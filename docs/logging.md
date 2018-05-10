<!--
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
-->
# Logging in OpenWhisk

## Default Logging Provider

OpenWhisk uses Logback as default logging provider via slf4j.

Logback can be configured in the configuration file [logback.xml](../common/scala/src/main/resources/logback.xml).

Besides other things this configuration file defines the default log level for OpenWhisk.
Akka Logging inherits the log level defined here.

## Enable debug-level logging for selected namespaces

For performance reasons it is recommended to leave the default log level on INFO level. For debugging purposes one can enable DEBUG level logging for selected namespaces
(aka special users). A list of namespace ids can be configured using the `nginx_special_users` property in the `group_vars/all` file of an environment.

Example:

```
nginx_special_users:
- 9cfe57b0-7fc1-4cf4-90b6-d7e9ea91271f
- 08f65e83-2d12-5b7c-b625-23df81c749a9
```

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

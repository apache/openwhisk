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
# YARN Support

The `YARNContainerFactory` enables launching action containers within a YARN cluster. It does not affect the deployment of OpenWhisk components (invoker, controller).

## Enable

To enable YARNContainerFactory, use the following TypeSafe Config properties

| property | required | details | example |
| --- | --- | --- | --- |
| `whisk.spi.ContainerFactoryProvider` | required | enable the YARNContainerFactory | org.apache.openwhisk.core.yarn.YARNContainerFactoryProvider |
| `whisk.yarn.masterUrl` | required | YARN Resource Manager endpoint to be accessed from the invoker |  http://localhost:8088 |
| `whisk.yarn.yarnLinkLogMessage` | optional (default true) | Display a log message with a link to YARN when using the default LogStore (or no log message) |  true |
| `whisk.yarn.serviceName` | optional (default openwhisk) | Name of the YARN Service created by the invoker. The invoker number will be appended. |  openwhisk-action-service |
| `whisk.yarn.authType` | optional (default simple) | Authentication type for YARN |  simple or kerberos |
| `whisk.yarn.kerberosPrincipal` | optional (default "") | Kerberos principal to use for the YARN service. Note: must include a hostname |  user1/hostA@REALM |
| `whisk.yarn.kerberosKeytabURI` | optional (default "") | Location of keytab accessible by all node managers |  hdfs:/user/user1/user1_hostA.keytab |
| `whisk.yarn.queue` | optional (default default) | Name of the YARN queue where the service will be created |  default |
| `whisk.yarn.memory` | optional (default 256) | Memory used by each YARN container |  256 |
| `whisk.yarn.cpus` | optional (default 1) | CPUs used by each YARN container |  1 |

To set these properties for your invoker, set the corresponding environment variables e.g.,
```properties
CONFIG_whisk_spi_ContainerFactoryProvider=org.apache.openwhisk.core.yarn.YARNContainerFactoryProvider
CONFIG_whisk_yarn_masterUrl=http://localhost:8088
CONFIG_whisk_yarn_yarnLinkLogMessage=true
CONFIG_whisk_yarn_serviceName=openwhisk-action-service
CONFIG_whisk_yarn_authType=simple

CONFIG_whisk_yarn_queue=default
CONFIG_whisk_yarn_memory=256
CONFIG_whisk_yarn_cpus=1
```

## HA
HA is supported. Each invoker will create its own YARN service with its invoker number appended to the configured service name (e.g. openwhisk-action-service-0).

## Security
By default, OpenWhisk does not authenticate when communicating with YARN. Optionally, Kerberos/SPNEGO authentication can be used via JaaS with a few steps:
* Set whisk.yarn.authType to "kerberos"
* Set the kerberosPrincipal and kerberosKeytabURI properties. These are used by the YARN service.
* Mount krb5.conf, login.conf, and keytab files into the invoker's docker container. For example:
    * -v "/etc/krb5.conf:/etc/krb5.conf"
    * -v "/home/user1/login.conf:/login.conf"
    * -v "/home/user1/user1.keytab:/user1.keytab"
* Run the invoker with the following java settings (e.g. via the INVOKER_OPTS environment variable):
    * -Djava.security.auth.login.config={Path to login.conf file}
    * -Djava.security.krb5.conf={Path to krb5.conf file}

Example login.conf:
```
com.sun.security.jgss.initiate {
     com.sun.security.auth.module.Krb5LoginModule required
     useKeyTab=true
     storeKey=true
     doNotPrompt=true
     keyTab="~/user1_hostA.keytab"
     principal="user1/hostA@REALM";
 };
```

## Known Issues

* Logs are not collected from action containers.

  For now, the YARN public URL will be included in the logs retrieved via the wsk CLI. Once log retrieval from external sources is enabled, logs from yarn containers would have to be routed to the external source, and then retrieved from that source.

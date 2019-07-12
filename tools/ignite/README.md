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

## Build

First build the ignite compatible Docker images for nodejs runtime by executing below command in `nodejs` folder

```bash
docker build -t whisk/ignite-nodejs-v12:latest  .
``` 

## Launch Standalone

```
$ ./gradlew :core:standalone:build
$ sudo java -Dwhisk.spi.ContainerFactoryProvider=org.apache.openwhisk.core.containerpool.ignite.IgniteContainerFactoryProvider \
      -jar bin/openwhisk-standalone.jar \
      -m tools/ignite/ignite-runtimes.json
```
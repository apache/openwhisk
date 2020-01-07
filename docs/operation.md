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

# Runtime configuration
## Change runtime to all managed invokers via controller. e.g.
```
curl -u ${username}:${password} -X POST http://${controllerAddress}:${controllerPort}/config/runtime -d '{...}'
```
Note: you can add `?limit` to specify target invokers, e.g. specify invoker0 and invoker1
```
curl -u ${username}:${password} -X POST http://${controllerAddress}:${controllerPort}/config/runtime?limit=0:1 -d '{...}'
```
## Get prewarm container info on assigned invoker
```
curl -u ${username}:${password} -X GET 'http://${invokerAddress}:${invokerPort}/getRuntime'
[{"kind":"nodejs:10","memory":256,"number":2}, {"kind":"nodejs:6","memory":256,"number":1}]
```

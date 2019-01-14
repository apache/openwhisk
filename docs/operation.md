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

# Prewarm container operation
## Add prewarm container on assigned invoker, e.g:
```
curl -u ${username}:${password} -X POST http://${invokerAddress}:${invokerPort}/prewarmContainer -d '
{
 "runtimes": {
  "nodejs": [{
   "kind": "nodejs:6",
   "default": true,
   "image": {
    "prefix": "openwhisk",
    "name": "nodejs6action",
    "tag": "latest"
   },
   "deprecated": false,
   "attached": {
    "attachmentName": "codefile",
    "attachmentType": "text/plain"
   },
   "stemCells": [{
    "count": 2,
    "memory": "128 MB"
   }]
  }]
 }
}
'
```
You can also send this request to controller to add prewarm container to all managed invokers
## Delete prewarm container on assigned invoker, e.g:
```
curl -u ${username}:${password} -X DELETE http://${invokerAddress}:${invokerPort}/prewarmContainer -d '
{
 "runtimes": {
  "nodejs": [{
   "kind": "nodejs:6",
   "default": true,
   "image": {
    "prefix": "openwhisk",
    "name": "nodejs6action",
    "tag": "latest"
   },
   "deprecated": false,
   "attached": {
    "attachmentName": "codefile",
    "attachmentType": "text/plain"
   },
   "stemCells": [{
    "count": 2,
    "memory": "128 MB"
   }]
  }]
 }
}
'
```
You can also send this request to controller to delete prewarm container to all managed invokers
## Get prewarm container from assigned invoker
```
curl  -X GET 'http://${invokerAddress}:${invokerPort}/prewarmContainerNumber?kind=nodejs:6&memory=128'
```

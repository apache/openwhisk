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
# Title

Currently, OpenWhisk supports returning a JSON object only, e.g.
```shell
# wsk action invoke hello -r
{
    "greeting": "Hello stranger!"
}
```
It is necessary to support returning an array too as an array is also a proper JSON object, e.g.
```shell
# wsk action invoke hello-array -r
[
    "a",
    "b"
]
```
The sequence action should be considered support as well.

# Status
* Current state: In-progress
* Author(s): @ningyougang

# Summary and Motivation

This POEM proposes a new feature that allows user to write their own action which supports an array result.
So actions would be able to return a JSON object or an array.

# Proposed changes
## Openwhisk main repo
Make controller and invoker support both a JSON object and an array result.

## Runtime repos
All runtime images should support an array result. e.g.

* nodejs (supports by default)
* go
* java
* python
* php
* shell
* docker
* ruby
* dotnet
* rust
* swift 
* deno
* ballerina

## Openwhisk-cli repo
* The `wsk` CLI needs to support parsing an array result when executing actions.
* The `wsk` CLI needs to support parsing an array result when getting activations.

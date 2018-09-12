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

# Running Tests

This module hosts all the unit and integration test for this repo. Command examples given below are meant to be executed
from project root.

To run all tests

    $ ./gradlew tests:test

This requires the OpenWhisk system to be setup and running locally.

## Running Unit Tests

To just run the unit tests

    $ ansible-playbook -i ansible/environments/local ansible/properties.yml
    $ ./gradlew tests:testUnit

## Running System Basic Tests

To just run system basic test against an existing running setup you can pass on the server details and auth via system properties

    $ ./gradlew :tests:testSystemBasic -Dwhisk.auth="23bc46b1-71f6-4ed5-8c54-816aa4f8c502:123zO3xZCLrMN6v2BKK1dXYFpXlPkccOFqm12CdAsMgRU4VrNZ9lyGVCGuMDGIwP" -Dwhisk.server=https://localhost -Dopenwhisk.home=`pwd`

Here

* `whisk.auth` - Auth key for a test user account. For a setup using default credentials it can be `guest` key. (env `WHISK_AUTH`)
* `whisk.server` - Edge Host Url of the OpenWhisk setup. (env `WHISK_SERVER`)
* `opnewhisk.home` - Base directory of your OpenWhisk source tree. (env `OPENWHISK_HOME`)

If required you can relax the SSL check by passing `-Dwhisk.ssl.relax=true`. All these properties can also be provided via env variables.

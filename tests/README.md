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

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
# openwhisk-karate
This directory contains all the Test Cases needed to do various forms of automated test on the OpenWhisk environments. These tests will also complement the existing Scala based Test cases. These tests are based on the Cucumber BDD model and enables quick on-boarding of the tests.
These tests are isolated from the existing ScalaTests and are focused more towards testing User Behaviours.
These tests are based on Karate framework.

### How to run functional test
1. Navigate to the project root folder
2. Use the following command to run the smoke tests located in the Karate Smoke Tests Package: `./gradlew tests:smokeTestKarate -Dadminauth='d2hpc2tfYWRtaW46c29tZV9wYXNzdzByZA==' -Dadminbaseurl=http://localhost:5984 -Dserver=https://localhost:443` (This will run all the tests in org.apache.openwhisk.smoketests package.)

Here

* `adminauth` - Base64 encoded DB credentials (`admin.user:admin.pass`)
* `adminbaseurl` - Url of the DB
* `server` - Edge Host Url of the OpenWhisk setup. (env `WHISK_SERVER`)


### How to add more tests
1. Select a package(Type of test).Example Smoke test
2. Add a new feature file which has your test with the following tags `@smoke`

### How to add a new test type
1. Create a package in `src/test/java`.
2. We can create test types like regression, sanity etc. For example `org.apache.openwhisk.sanitytests`
3. Create a feature and runner file inside the above package

### more info
1. https://github.com/intuit/karate/tree/master/karate-demo
2. https://github.com/intuit/karate
3. https://gatling.io/docs/2.3/general/simulation_setup/

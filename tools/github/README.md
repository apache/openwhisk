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

# Travis Setup

Travis build is configured to perform build of this repo in multiple parallel jobs as listed below.

1. Unit Tests - Runs the test which only need database service.
2. System Tests - Runs those tests which need complete OpenWhisk system up and running.
3. Performance test suite - Run basic performance tests with the objective to check if tests are working or not.

These jobs make use of following scripts

1. `scan.sh` - Performs various code scan task like python flake scan, scala formatting etc.
2. `setupPrereq.sh` - Performs setup if basis prerequisites like database setup and property file generation.
3. `distDocker.sh` - Builds the various docker containers.
4. `setupSystem.sh` - Runs the various containers which are part of an OpenWhisk setup like Controller, Invoker etc.
5. `runTests.sh` - Runs the tests. It make use of `ORG_GRADLE_PROJECT_testSetName` env setting to determine which test
   suite to run.
6. `checkAndUploadLogs.sh` -  Collects the logs, checks them and uploads them to https://openwhisk.box.com/v/travis-logs.

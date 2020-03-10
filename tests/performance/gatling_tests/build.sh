#!/bin/bash
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

set -e

GRADLEW_PATH=${OPENWHISK_HOME:-../../../../../../../../..}

if [ -f ".built" ]; then
  echo "Test zip artifacts already built, skipping"
  exit 0
fi

# Let gradle set the source and target versions to let it sort out if it can produce
# byte code that is compatible with java8.
(cd src/gatling/resources/data/src/java && "$GRADLEW_PATH/gradlew" build && cp build/libs/gatling-1.0.jar ../../javaAction.jar)
touch .built

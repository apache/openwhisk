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

if [ -f ".built" ]; then
  echo "Test zip artifacts already built, skipping"
  exit 0
fi

# need java 8 to build java actions since that's the version of the runtime currently
jv=$(java -version 2>&1 | head -1 | awk -F'"' '{print $2}')
if [[ $jv == 1.8.* ]]; then
  echo "java version is $jv (ok)"
  (cd unicode.tests/src/java/unicode && ../../../../../../../gradlew build && cp build/libs/unicode-1.0.jar ../../../java-8.bin)
  (cd src/java/sleep && ../../../../../../gradlew build && cp build/libs/sleep-1.0.jar ../../../sleep.jar)
else
  echo "java version is $jv (not ok)"
  echo "skipping java actions"
fi

(cd blackbox && zip ../blackbox.zip exec)
(cd python-zip && zip ../python.zip -r .)
(cd zippedaction && npm install && zip ../zippedaction.zip -r .)

touch .built

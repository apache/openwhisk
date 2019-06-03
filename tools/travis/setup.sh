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

# retries a command for five times and exits with the non-zero exit if even after
# the retries the command did not succeed.
function retry() {
  local exitcode=0
  for i in {1..5};
  do
    exitcode=0
    "$@" && break || exitcode=$? && echo "$i. attempt failed. Will retry $((5-i)) more times!" && sleep 1;
  done
  if [ $exitcode -ne 0 ]; then
    exit $exitcode
  fi
}

# Python
pip install --user couchdb

# Ansible
pip install --user ansible==2.5.2

# Azure CosmosDB
pip install --user pydocumentdb

# Support the revises log upload script
pip install --user humanize requests

# Basic check that all code compiles and depdendencies are downloaded correctly.
# Compiling the tests will compile all components as well.
#
# Downloads the gradle wrapper, dependencies and tries to compile the code.
# Retried 5 times in case there are network hiccups.
TERM=dumb retry ./gradlew :tests:compileTestScala

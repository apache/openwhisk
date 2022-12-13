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

#if [[ $TEST_SUITE =~ Dummy ]]
#then echo skipping setup ; exit 0
#fi

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

# setup docker to listen in port 4243
sudo systemctl stop docker
sudo sed -i -e 's!/usr/bin/dockerd -H fd://!/usr/bin/dockerd -H tcp://0.0.0.0:4243 -H fd://!' /lib/systemd/system/docker.service
sudo systemctl daemon-reload
sudo systemctl start docker

# installing right version of jdk
JDK=https://github.com/ibmruntimes/semeru11-binaries/releases/download/jdk-11.0.12%2B7_openj9-0.27.0/ibm-semeru-open-jdk_x64_linux_11.0.12_7_openj9-0.27.0.tar.gz
curl -sL $JDK | sudo tar xzvf - -C /usr/local
JAVA="$(which java)"
sudo mv "$JAVA" "$JAVA"."$(date +%s)"
sudo ln -sf /usr/local/jdk*/bin/java $JAVA
java -version

# Python
python -m pip install --user couchdb

# Ansible (warning you need jinja < 3.1 with this version)
python -m pip install --user 'jinja2<3.1' ansible==2.8.18

# Azure CosmosDB
python -m pip install --user pydocumentdb

# Support the revises log upload script
python -m pip install --user humanize requests

# Scan code before compiling the code
tools/github/scan.sh

# Preload alpine 3.5 to avoid issues with depending images
retry docker pull alpine:3.5

# exit if dummy test suite skipping the long compilation when debugging
if [[ $TEST_SUITE =~ Dummy ]]
then echo skiping setup ; exit 0
fi

# Basic check that all code compiles and dependencies are downloaded correctly.
# Compiling the tests will compile all components as well.
#
# Downloads the gradle wrapper, dependencies and tries to compile the code.
# Retried 5 times in case there are network hiccups.
TERM=dumb retry ./gradlew :tests:compileTestScala

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

# Build script for Travis-CI.

SCRIPTDIR=$(cd $(dirname "$0") && pwd)
ROOTDIR="$SCRIPTDIR/../.."
HOMEDIR="$SCRIPTDIR/../../../"
UTILDIR="$HOMEDIR/incubator-openwhisk-utilities/"

# clone the openwhisk utilities repo.
cd $HOMEDIR
git clone https://github.com/apache/incubator-openwhisk-utilities.git

# run the scancode util. against project source code starting at its root
cd $UTILDIR
scancode/scanCode.py --config scancode/ASF-Release.cfg $ROOTDIR

# run scalafmt checks
cd $ROOTDIR
TERM=dumb ./gradlew checkScalafmtAll

# lint tests to all be actually runnable
MISSING_TESTS=$(grep -rL "RunWith" --include="*Tests.scala" tests)
if [ -n "$MISSING_TESTS" ]
then
  echo "The following tests are missing the 'RunWith' annotation"
  echo $MISSING_TESTS
  exit 1
fi

cd $ROOTDIR/ansible

$ANSIBLE_CMD setup.yml -e mode=HA
$ANSIBLE_CMD prereq.yml
$ANSIBLE_CMD couchdb.yml
$ANSIBLE_CMD initdb.yml
$ANSIBLE_CMD apigateway.yml

cd $ROOTDIR

TERM=dumb ./gradlew distDocker -PdockerImagePrefix=testing $GRADLE_PROJS_SKIP

cd $ROOTDIR/ansible

$ANSIBLE_CMD wipe.yml
$ANSIBLE_CMD openwhisk.yml

cd $ROOTDIR
cat whisk.properties
TERM=dumb ./gradlew :tests:testCoverageLean :tests:reportCoverage

cd $ROOTDIR/ansible
$ANSIBLE_CMD logs.yml

cd $ROOTDIR
tools/build/checkLogs.py logs

bash <(curl -s https://codecov.io/bash)

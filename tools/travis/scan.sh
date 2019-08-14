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
SECONDS=0
SCRIPTDIR=$(cd $(dirname "$0") && pwd)
ROOTDIR="$SCRIPTDIR/../.."
HOMEDIR="$SCRIPTDIR/../../../"
UTILDIR="$HOMEDIR/openwhisk-utilities/"

cd $ROOTDIR
./tools/travis/flake8.sh  # Check Python files for style and stop the build on syntax errors

# clone the openwhisk utilities repo.
cd $HOMEDIR
git clone https://github.com/apache/openwhisk-utilities.git

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

echo "Time taken for ${0##*/} is $SECONDS secs"

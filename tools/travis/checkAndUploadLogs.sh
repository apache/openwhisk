#!/usr/bin/env bash

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

# Disable abort script at first error as we require the logs to be uploaded
# even if check and log collection fails
# set -e

SECONDS=0
SCRIPTDIR=$(cd $(dirname "$0") && pwd)
ROOTDIR="$SCRIPTDIR/../.."

cd $ROOTDIR

LOG_NAME=$1
TAGS=${2-""}
LOG_TAR_NAME="${LOG_NAME}_${TRAVIS_BUILD_ID}-$TRAVIS_BRANCH.tar.gz"

# Perf logs are typically about 20MB and thus rapidly fill our box account.
# Disable upload to reduce the interval at which we need to manually clean logs from box.
if [ "$LOG_NAME" == "perf" ]; then
    echo "Skipping upload of perf logs to conserve space"
    exit 0
fi

ansible-playbook -i ansible/environments/local ansible/logs.yml

./tools/build/checkLogs.py logs "$TAGS"

./tools/travis/box-upload.py "$TRAVIS_BUILD_DIR/logs" "$LOG_TAR_NAME"

echo "Uploaded Logs with name $LOG_TAR_NAME"
echo "Time taken for ${0##*/} is $SECONDS secs"

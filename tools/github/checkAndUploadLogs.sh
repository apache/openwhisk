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

# check variables
for i in S3_LOG_BUCKET AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_REGION
do
  if test -z "${!i}" 
  then echo "Required Environment Variable Missing: $i" ; exit 1
  fi
done

# Disable abort script at first error as we require the logs to be uploaded
# even if check and log collection fails
# set -e


SECONDS=0
SCRIPTDIR=$(cd $(dirname "$0") && pwd)
ROOTDIR="$SCRIPTDIR/../.."

cd $ROOTDIR

LOG_NAME="$1"

# tags is db only when the test is unit
TAGS=""
[[ "$2" == "Unit" ]] && TAGS="db"

LOG_DIR="$(date +%Y-%m-%d)/${LOG_NAME}-${GH_BUILD}-${GH_BRANCH}"

# Perf logs are typically about 20MB and thus rapidly fill our box account.
# Disable upload to reduce the interval at which we need to manually clean logs from box.
if [ "$LOG_NAME" == "Performance" ]; then
    echo "Skipping upload of perf logs to conserve space"
    exit 0
fi

ansible-playbook -i ansible/environments/local ansible/logs.yml

./tools/build/checkLogs.py logs "$TAGS"

./tools/github/s3-upload.sh "$PWD/logs" "$LOG_DIR"

echo "Time taken for ${0##*/} is $SECONDS secs"

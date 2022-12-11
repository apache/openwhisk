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

# check variables
for i in S3_LOG_BUCKET AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY
do
  if test -z "${!i}"
  then echo "Please set $i" ; exit 1
  fi
done

if [[ -z "$1" ]] || [[ -z "$2" ]]
then echo "usage: <source-dir> <target-path>" ; exit 1
fi

FROM="$1"
TO="$2"

BROWSER="https://raw.githubusercontent.com/qoomon/aws-s3-bucket-browser/master/index.html"
BUCKET_URL="https://$S3_LOG_BUCKET.s3.$AWS_REGION.amazonaws.com/"

# install rclone
if ! which rclone
then curl https://rclone.org/install.sh | sudo bash
fi

RCLONE="rclone --config /dev/null \
  --s3-provider AWS \
  --s3-region $AWS_REGION \
  --s3-acl public-read \
  --s3-access-key-id  $AWS_ACCESS_KEY_ID \
  --s3-secret-access-key $AWS_SECRET_ACCESS_KEY"

curl -s "$BROWSER" |\
  sed -e 's!bucketUrl: undefined!bucketUrl: "'$BUCKET_URL'"!' |\
  $RCLONE rcat ":s3:$S3_LOG_BUCKET/index.html"

$RCLONE copyto "$FROM" ":s3:$S3_LOG_BUCKET/$TO/"
echo "logurl=${BUCKET_URL}index.html#$TO" >>"${GITHUB_OUTPUT:-/dev/stdout}"




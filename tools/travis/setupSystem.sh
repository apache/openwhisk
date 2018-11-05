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
RUNTIMES_MANIFEST=${1:-"/ansible/files/runtimes.json"}

cd $ROOTDIR/ansible

$ANSIBLE_CMD openwhisk.yml -e manifest_file="$RUNTIMES_MANIFEST"
$ANSIBLE_CMD apigateway.yml
$ANSIBLE_CMD routemgmt.yml

echo "Time taken for ${0##*/} is $SECONDS secs"

#!/bin/sh
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
SCRIPTDIR="$(cd "$(dirname "$0")"; pwd)"
ROOTDIR="$SCRIPTDIR/../../.."

# Build Openwhisk
cd $ROOTDIR
TERM=dumb ./gradlew distDocker -PdockerImagePrefix=testing $GRADLE_PROJS_SKIP

# Deploy Openwhisk
cd $ROOTDIR/ansible
ANSIBLE_CMD="$ANSIBLE_CMD -e limit_invocations_per_minute=999999 -e limit_invocations_concurrent=999999 -e controller_client_auth=false -e userLogs_spi=\"org.apache.openwhisk.core.containerpool.logging.LogDriverLogStoreProvider\""

$ANSIBLE_CMD setup.yml
$ANSIBLE_CMD prereq.yml
$ANSIBLE_CMD couchdb.yml
$ANSIBLE_CMD initdb.yml
$ANSIBLE_CMD wipe.yml

$ANSIBLE_CMD kafka.yml
$ANSIBLE_CMD controller.yml
$ANSIBLE_CMD invoker.yml
$ANSIBLE_CMD edge.yml

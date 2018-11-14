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

# Host to use. Needs to include the protocol.
host=$1
# Credentials to use for the test. USER:PASS format.
credentials=$2
# Name of the action to create and test.
action=$3
# Path to action src
action_src=$4
# Concurrency setting
action_concurrency=${5:-1}

# jq will json encode the src (need to strip leading/trailing quotes that jq adds)
#action_code=$(jq -cs . "$action_src" | sed 's/^.\(.*\).$/\1/')
action_code=$(cat "$action_src")

# setup the action json to create the action
action_json='{"namespace":"_","name":"'"$action"'","exec":{"kind":"nodejs:default","code":""},"limits":{"concurrency":'"$action_concurrency"'}}'
action_json=$(echo  "$action_json" | jq -c --arg code "$action_code" '.exec.code=($code)')


# create a noop action
echo "Creating action $action"
curl -k -u "$credentials" "$host/api/v1/namespaces/_/actions/$action" -XPUT -d "$action_json" -H "Content-Type: application/json"

# run the noop action
echo "Running $action once to assert an intact system"
curl -k -u "$credentials" "$host/api/v1/namespaces/_/actions/$action?blocking=true" -XPOST

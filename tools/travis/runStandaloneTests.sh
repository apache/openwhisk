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

set -e

SCRIPTDIR=$(cd $(dirname "$0") && pwd)
ROOTDIR="$SCRIPTDIR/../.."

export ORG_GRADLE_PROJECT_testSetName="REQUIRE_STANDALONE"
export GRADLE_COVERAGE=true

cd $ROOTDIR/ansible
$ANSIBLE_CMD setup.yml
$ANSIBLE_CMD properties.yml -e manifest_file="/ansible/files/runtimes-nodeonly.json"
$ANSIBLE_CMD downloadcli-github.yml

# Install kubectl
curl -Lo ./kubectl https://storage.googleapis.com/kubernetes-release/release/v1.16.1/bin/linux/amd64/kubectl
chmod +x kubectl
sudo cp kubectl /usr/local/bin/kubectl

# Install kind
curl -Lo ./kind https://github.com/kubernetes-sigs/kind/releases/download/v0.5.1/kind-linux-amd64
chmod +x kind
sudo cp kind /usr/local/bin/kind

kind create cluster --wait 5m
export KUBECONFIG="$(kind get kubeconfig-path)"
kubectl config set-context --current --namespace=default


cd $ROOTDIR
TERM=dumb ./gradlew :core:standalone:build \
  :core:monitoring:user-events:distDocker

cd $ROOTDIR
TERM=dumb ./gradlew :core:standalone:cleanTest \
  :core:standalone:test \
  :core:monitoring:user-events:reportTestScoverage

# Run test in end as it publishes the coverage also
cd $ROOTDIR/tools/travis
./runTests.sh

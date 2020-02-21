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
set -x

sudo sudo apt-get install -y apt-transport-https ca-certificates curl gnupg-agent software-properties-common
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo apt-key add -
sudo apt-key fingerprint 0EBFCD88

sudo add-apt-repository \
   "deb [arch=amd64] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable"

sudo add-apt-repository \
    "deb [arch=$(uname -m | sed -e 's/x86_64/amd64/g')] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable"
sudo apt-get -y update

# AUFS
# Use '-virtual' package to support docker tests of the script
sudo apt-get --no-install-recommends -y install linux-image-extra-virtual

# DOCKER
sudo apt-get install -y docker-ce=18.06.3~ce~3-0~ubuntu containerd.io
sudo apt-mark hold docker-ce

# enable (security - use 127.0.0.1)
sudo -E bash -c 'echo '\''DOCKER_OPTS="-H unix:///var/run/docker.sock --storage-driver=aufs"'\'' >> /etc/default/docker'
sudo gpasswd -a "$(whoami)" docker

sudo service docker restart

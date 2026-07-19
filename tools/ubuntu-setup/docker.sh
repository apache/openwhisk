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

sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg

# Add the GPG key signatures
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

# Register the stable Ubuntu Jammy APT source line channel
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt-get update

# DOCKER
# Force the exact target version
sudo apt-get install -y \
  docker-ce=5:27.3.1-1~ubuntu.22.04~jammy \
  docker-ce-cli=5:27.3.1-1~ubuntu.22.04~jammy \
  containerd.io

# CRITICAL: Lock the packages so 'apt upgrade' cannot update them automatically later
sudo apt-mark hold docker-ce docker-ce-cli

# enable (security - use 127.0.0.1)
sudo -E bash -c 'echo '\''DOCKER_OPTS="-H unix:///var/run/docker.sock --storage-driver=aufs"'\'' >> /etc/default/docker'
sudo gpasswd -a "$(whoami)" docker

sudo service docker restart

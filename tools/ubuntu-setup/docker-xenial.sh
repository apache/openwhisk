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

#
#  WARNING:  This is EXPERIMENTAL support for running OpenWhisk on the latest
#            stable version of Docker CE on Ubuntu Xenial or later.  Proceed
#            at your own risk.
#
#  Currently, ./all.sh does not support running this shell script.ls
#

set -e
set -x

sudo apt-get -y install apt-transport-https ca-certificates curl software-properties-common
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo apt-key add -
sudo apt-key fingerprint 0EBFCD88

sudo add-apt-repository \
    "deb [arch=$(uname -m | sed -e 's/x86_64/amd64/g')] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable"
sudo apt-get -y update

sudo apt-get purge lxc-docker || /bin/true
sudo apt-cache policy docker-engine

# DOCKER

# NOTE: For the moment, this script will use the latest stable version of
#       Docker CE.  When OpenWhisk locks down on a version of Docker CE to use,
#       it can then be locked in using the commented lines
#sudo apt-get install -y docker-ce=$docker_ce_version
#sudo apt-mark hold docker-engine
sudo apt-get install -y docker-ce  # Replace with lines above to lock in version

# enable (security - use 127.0.0.1)
sudo -E bash -c 'echo '\''DOCKER_OPTS="-H tcp://0.0.0.0:4243 -H unix:///var/run/docker.sock --storage-driver=aufs"'\'' >> /etc/default/docker'
sudo gpasswd -a "$(whoami)" docker

sudo service docker restart

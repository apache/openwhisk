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

BASEDIR=$(dirname "$0")
echo "$BASEDIR"

sudo gpasswd -a travis docker
sudo usermod -aG docker travis
#sudo -E bash -c 'echo '\''DOCKER_OPTS="-H tcp://0.0.0.0:4243 -H unix:///var/run/docker.sock --storage-driver=overlay --userns-remap=default"'\'' > /etc/default/docker'

# Docker
sudo apt-get clean
sudo apt-get update

# Need to update dpkg due to known issue: https://bugs.launchpad.net/ubuntu/+source/dpkg/+bug/1730627
sudo apt-get install -y apt-transport-https ca-certificates curl gnupg-agent software-properties-common dpkg
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo apt-key add -
sudo apt-key fingerprint 0EBFCD88

# This is required because libseccomp2 (>= 2.3.0) is not provided in trusty by default
sudo add-apt-repository -y ppa:ubuntu-sdk-team/ppa

sudo add-apt-repository \
    "deb [arch=$(uname -m | sed -e 's/x86_64/amd64/g')] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable"

sudo apt-get update
sudo apt-get -o Dpkg::Options::="--force-confold" --force-yes -y install docker-ce=18.06.3~ce~3-0~ubuntu containerd.io
# daemon.json and flags does not work together. Overwritting the docker.service file
# to remove the host flags. - https://docs.docker.com/config/daemon/#troubleshoot-conflicts-between-the-daemonjson-and-startup-scripts
sudo mkdir -p /etc/systemd/system/docker.service.d
sudo cp $BASEDIR/docker.conf /etc/systemd/system/docker.service.d/docker.conf
# setup-docker will add configs to /etc/docker/daemon.json
sudo python $BASEDIR/setup-docker.py
sudo cat /etc/docker/daemon.json
sudo systemctl daemon-reload
sudo systemctl restart docker
sudo systemctl status docker.service
echo "Docker Version:"
docker version
echo "Docker Info:"
docker info

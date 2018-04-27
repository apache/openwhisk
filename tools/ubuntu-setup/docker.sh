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

sudo apt-get -y install apt-transport-https ca-certificates
sudo apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys 36A1D7869245C8950F966E92D8576A8BA88D21E9
sudo sh -c "echo deb https://apt.dockerproject.org/repo ubuntu-trusty main  > /etc/apt/sources.list.d/docker.list"
sudo apt-get -y update

sudo apt-get purge lxc-docker
sudo apt-cache policy docker-engine

# AUFS
# Use '-virtual' package to support docker tests of the script and still run under Vagrant
sudo apt-get --no-install-recommends -y install linux-image-extra-virtual

# DOCKER
sudo apt-get install -y --force-yes docker-engine=1.12.0-0~trusty
sudo apt-mark hold docker-engine

# enable (security - use 127.0.0.1)
sudo -E bash -c 'echo '\''DOCKER_OPTS="-H tcp://0.0.0.0:4243 -H unix:///var/run/docker.sock --storage-driver=aufs"'\'' >> /etc/default/docker'
sudo gpasswd -a "$(whoami)" docker


sudo service docker restart

# do not run this command without a vagrant reload during provisioning
# it gives an error that docker is not up (which the reload fixes).
# sudo docker version

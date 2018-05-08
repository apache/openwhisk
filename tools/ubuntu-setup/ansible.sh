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

sudo pip install --upgrade setuptools pip
sudo apt-get install -y software-properties-common
sudo apt-add-repository -y ppa:ansible/ansible
sudo apt-get update
sudo apt-get install -y python-dev libffi-dev libssl-dev
sudo pip install markupsafe
sudo pip install ansible==2.5.2
sudo pip install jinja2==2.9.6
sudo pip install docker==2.2.1    --ignore-installed  --force-reinstall
sudo pip install httplib2==0.9.2  --ignore-installed  --force-reinstall
sudo pip install requests==2.10.0 --ignore-installed  --force-reinstall

ansible --version
ansible-playbook --version

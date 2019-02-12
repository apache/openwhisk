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

JAVA_SOURCE=${1:-"open"}

if [ "$JAVA_SOURCE" != "oracle" ] ; then
    if [ "$(lsb_release -cs)" == "trusty" ]; then
        sudo apt-get install -y software-properties-common python-software-properties
        sudo add-apt-repository ppa:jonathonf/openjdk -y
        sudo apt-get update
    fi

    sudo apt-get install openjdk-8-jdk -y
else
    sudo apt-get install -y software-properties-common python-software-properties
    sudo add-apt-repository ppa:webupd8team/java -y
    sudo apt-get update

    echo 'oracle-java8-installer shared/accepted-oracle-license-v1-1 boolean true' | sudo debconf-set-selections
    sudo apt-get install oracle-java8-installer -y
fi

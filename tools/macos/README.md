<!--
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
-->

# Setting up OpenWhisk with Docker for Mac

OpenWhisk can run on a Mac host with [Docker for Mac](https://docs.docker.com/docker-for-mac/).
If you prefer to use Docker-machine, you can follow instructions in [docker-machine/README.md](docker-machine/README.md)

# Prerequisites

The following are required to build and deploy OpenWhisk from a Mac host:

- [Docker 18.06.3+](https://docs.docker.com/docker-for-mac/install/)
- [Open JDK 11](https://adoptopenjdk.net/releases.html#x64_mac)
- [Scala 2.12](http://scala-lang.org/download/)
- [Ansible 4.1.0](https://docs.ansible.com/ansible/latest/installation_guide/intro_installation.html)

**Tips:**
 1. Versions of Docker and Ansible are lower than the latest released versions, the versions used in OpenWhisk are pinned to have stability during continuous integration and deployment.<br>
 2. It is required to install Docker >= 18.06.2 because of this [CVE](https://cve.mitre.org/cgi-bin/cvename.cgi?name=CVE-2019-5736)


[Homebrew](http://brew.sh/) is an easy way to install all of these and prepare your Mac to build and deploy OpenWhisk. The following shell command is provided for your convenience to install `brew` with [Cask](https://github.com/caskroom/homebrew-cask) and bootstraps these to complete the setup. Copy the entire section below and paste it into your terminal to run it.

```bash
echo '
# install homebrew
/usr/bin/ruby -e "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/master/install)"
# install cask
brew tap homebrew/cask
# install for AdoptOpenJDK (java11)
brew tap AdoptOpenJDK/openjdk
# install java 11
brew install --cask adoptopenjdk11
# install scala
brew install scala
# install gnu tar
brew install gnu-tar
# install pip
sudo easy_install pip
# install script prerequisites
pip install docker==5.0.0 ansible==4.1.0 jinja2==3.0.1 couchdb==1.2 httplib2==0.19.1 requests==2.25.1 six=1.16.0
```

Make sure you correctly configure the environment variable $JAVA_HOME.

# Build
```bash
cd /your/path/to/openwhisk
./gradlew distDocker
```
**Tip** Using `gradlew` handles the installation of the correct version of Gradle to use.

# Deploy
Follow instructions in [ansible/README.md](../../ansible/README.md)

### Configure the CLI

#### Using brew

```bash
brew install wsk 
wsk property set --apihost https://localhost
wsk property set --auth `cat ansible/files/auth.guest`
```
#### Other methods
For more instructions see [Configure CLI doc](../../docs/cli.md).

### Use the wsk CLI
```bash
wsk action invoke /whisk.system/utils/echo -p message hello --result
{
    "message": "hello"
}
```

# Develop

## Running unit tests

> Unit tests require [Ansible setup](../../ansible/README.md) at the moment.

Bellow are the ansible commands required to prepare your machine:

```bash
cd ./ansible

ansible-playbook setup.yml -e mode=HA
ansible-playbook couchdb.yml
ansible-playbook initdb.yml
ansible-playbook wipe.yml

ansible-playbook properties.yml
```

To run the unit tests execute the command bellow from the project's root folder: 
```bash
# go back to project's root folder
cd ../
./gradlew -PtestSetName="REQUIRE_ONLY_DB" :tests:testCoverageLean 
```
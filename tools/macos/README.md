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

- [Docker 18.06.3](https://docs.docker.com/docker-for-mac/install/)
- [Open JDK 10](https://adoptopenjdk.net/releases.html#x64_mac)
- [Scala 2.11](http://scala-lang.org/download/)
- [Ansible 2.5.2](http://docs.ansible.com/ansible/intro_installation.html)

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
# install for AdoptOpenJDK (java10)
brew tap AdoptOpenJDK/openjdk
# install java 10
brew cask install adoptopenjdk10
# install scala
brew install scala
# install gnu tar
brew install gnu-tar
# install pip
sudo easy_install pip
# install script prerequisites
sudo -H pip install docker==2.2.1 ansible==2.5.2 jinja2==2.9.6 couchdb==1.1 httplib2==0.9.2 requests==2.10.0' | bash
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
Follow instructions in [Configure CLI](../../docs/cli.md)

### Use the wsk CLI
```bash
bin/wsk action invoke /whisk.system/utils/echo -p message hello --result
{
    "message": "hello"
}
```

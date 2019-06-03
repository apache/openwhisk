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

# Setting up OpenWhisk with Docker-machine

OpenWhisk can on a Mac using a virtual machine in which Docker daemon is running.
You will make provision of a virtual machine with Docker-machine and communicate with them via Docker remote API.

# Prerequisites

The following are required to build and deploy OpenWhisk from a Mac host:

- [Oracle VM VirtualBox](https://www.virtualbox.org/wiki/Downloads)
- [Docker 18.06.3](https://docs.docker.com/machine/install-machine/) (including `docker-machine`)
- [Java 8](http://www.oracle.com/technetwork/java/javase/downloads/index.html)
- [Scala 2.11](http://scala-lang.org/download/)
- [Ansible 2.5.2](http://docs.ansible.com/ansible/intro_installation.html)

**Tips:**
1. Versions of Docker and Ansible are lower than the latest released versions, the versions used in OpenWhisk are pinned to have stability during continues integration and deployment.
2. It is required to install Docker >= 18.06.2 because of this [CVE](https://cve.mitre.org/cgi-bin/cvename.cgi?name=CVE-2019-5736)

[Homebrew](http://brew.sh/) is an easy way to install all of these and prepare your Mac to build and deploy OpenWhisk. The following shell command is provided for your convenience to install `brew` with [Cask](https://github.com/caskroom/homebrew-cask) and bootstraps these to complete the setup. Copy the entire section below and paste it into your terminal to run it.

```bash
echo '
# install homebrew
/usr/bin/ruby -e "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/master/install)"
# install cask
brew tap caskroom/cask
# install virtualbox
brew cask install virtualbox
# install docker 1.12.0
brew install https://raw.githubusercontent.com/Homebrew/homebrew-core/33301827c3d770bfd49f0e50d84e0b125b06b0b7/Formula/docker.rb
# install docker-machine
brew install docker-machine
# install java 8
brew cask install java
# install scala
brew install scala
# install gnu tar
brew install gnu-tar
# install pip
sudo easy_install pip
# install script prerequisites
sudo -H pip install docker==2.2.1 ansible==2.5.2 jinja2==2.9.6 couchdb==1.1 httplib2==0.9.2 requests==2.10.0' | bash
```

# Create and configure Docker machine

It is recommended that you create a virtual machine `whisk` with at least 4GB of RAM.

```bash
docker-machine create -d virtualbox \
   --virtualbox-memory 4096 \
   --virtualbox-boot2docker-url=https://github.com/boot2docker/boot2docker/releases/download/v1.12.0/boot2docker.iso \
    whisk # the name of your docker machine
```
Note that by default the third octet chosen by docker-machine will be 99. If you've multiple docker machines
and want to ensure that the ip of the created whisk vm isn't dependent of the machine start order then provide `--virtualbox-hostonly-cidr "192.168.<third_octet>.1/24"` in order to create a dedicated virtual network interface.

The Docker virtual machine requires some tweaking to work from the Mac host with OpenWhisk.
The following [script](./tweak-dockermachine.sh) will disable TLS, add port forwarding
within the VM and routes `172.17.x.x` from the Mac host to the Docker virtual machine.
Enter your sudo Mac password when prompted.

```bash
cd /your/path/to/openwhisk
./tools/macos/docker-machine/tweak-dockermachine.sh
```

The final output of the script should resemble the following two lines.
```bash
Run the following:
export DOCKER_HOST="tcp://192.168.99.100:4243" # your Docker virtual machine IP may vary
```

The Docker host reported by `docker-machine ip whisk` will give you the IP address.
Currently, the system requires that you use port `4243` to communicate with the Docker host
from OpenWhisk.

Ignore errors messages from `docker-machine ls` for the `whisk` virtual machine, this is due
to the configuration of the port `4243` vs. `2376`
```bash
NAME      ACTIVE   DRIVER       STATE     URL                         SWARM   DOCKER    ERRORS
whisk     -        virtualbox   Running   tcp://192.168.99.100:2376           Unknown   Unable to query docker version: Cannot connect to the docker engine endpoint
```

To verify that docker is configure properly with `docker-machine` run `docker ps`, you should not see any errors. Here is an example output:
```bash
CONTAINER ID        IMAGE               COMMAND                  CREATED             STATUS              PORTS                    NAMES

```

You may find it convenient to set these environment variables in your bash profile (e.g., `~/.bash_profile` or `~/.profile`).
```bash
export OPENWHISK_HOME=/your/path/to/openwhisk
export DOCKER_HOST=tcp://$(docker-machine ip whisk):4243
```

The tweaks to the Docker machine persist across reboots.
However one of the tweaks is applied on the Mac host and must be applied
again if you reboot your Mac. Without it, some tests which require direct
communication with Docker containers will fail. To run just the Mac host tweaks,
run the following [script](./tweak-dockerhost.sh). Enter your sudo Mac password when prompted.
```bash
cd /your/path/to/openwhisk
./tools/macos/docker-machine/tweak-dockerhost.sh
```

# Build
```bash
cd /your/path/to/openwhisk
./gradlew distDocker
```
**Tip** Using `gradlew` handles the installation of the correct version of Gradle to use.

# Deploy

```bash
brew install python
pip install ansible==2.5.0
pip install jinja2==2.9.6

cd ansible
ansible-playbook -i environments/docker-machine setup.yml [-e docker_machine_name=whisk]
```

**Hint:** If you omit the optional `-e docker_machine_name` parameter, it will default to "whisk".
If your docker-machine VM has a different name you may pass it via the `-e docker_machine_name` parameter.

After this there should be a `hosts` file in the `ansible/environments/docker-machine` directory.

To verify the hosts file you can do a quick ping to the docker machine:

```bash
cd ansible
ansible all -i environments/docker-machine -m ping
```

Should result in something like:

```bash
ansible | SUCCESS => {
    "changed": false,
    "ping": "pong"
}
192.168.99.100 | SUCCESS => {
    "changed": false,
    "ping": "pong"
}
```

Follow remaining instructions from [Using Ansible](../../../ansible/README.md#using-ansible) section in [ansible/README.md](../../../ansible/README.md)

### Configure the CLI
Follow instructions in [Configure CLI](../../../docs/cli.md)

### Use the wsk CLI
```bash
bin/wsk action invoke /whisk.system/utils/echo -p message hello --result
{
    "message": "hello"
}
```

# How to setup a Mac host for OpenWhisk

One way to develop or deploy OpenWhisk on a Mac is to use [docker-machine](https://docs.docker.com/machine/install-machine/) which runs the Docker daemon inside a virtual machine accessible from the Mac host.

# Prerequisites

The following are required to build and deploy OpenWhisk from a Mac host:

- [Oracle VM VirtualBox](https://www.virtualbox.org/wiki/Downloads)
- [Docker 1.12.0](https://docs.docker.com/engine/installation/mac/) (including `docker-machine`)
- [Java 8](http://www.oracle.com/technetwork/java/javase/downloads/index.html)
- [Scala 2.11](http://scala-lang.org/download/)
- [Ansible 2.1.2.0](http://docs.ansible.com/ansible/intro_installation.html)

**Tip** Versions of Docker and Ansible are lower than the latest released versions, the versions used in OpenWhisk are pinned to have stability during continues integration and deployment.


[Homebrew](http://brew.sh/) is an easy way to install all of these and prepare your Mac to build and deploy OpenWhisk. The following shell command is provided for your convenience to install `brew` with [Cask](https://github.com/caskroom/homebrew-cask) and bootstraps these to complete the setup. Copy the entire section below and paste it into your terminal to run it.

```
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
# install pip
sudo easy_install pip
# install script prerequisites
sudo -H pip install ansible==2.1.2.0 jsonschema couchdb' | bash
```

# Create and configure Docker machine

It is recommended that you create a virtual machine `whisk` with at least 4GB of RAM.

```
docker-machine create -d virtualbox \
   --virtualbox-memory 4096 \
   --virtualbox-boot2docker-url=https://github.com/boot2docker/boot2docker/releases/download/v1.12.0/boot2docker.iso \
    whisk # the name of your docker machine
```

The Docker virtual machine requires some tweaking to work from the Mac host with OpenWhisk.
The following [script](./tweak-dockermachine.sh) will disable TLS, add port forwarding
within the VM and routes `172.17.x.x` from the Mac host to the Docker virtual machine.
Enter your sudo Mac password when prompted.

```
cd /your/path/to/openwhisk
./tools/macos/tweak-dockermachine.sh
```

The final output of the script should resemble the following two lines.
```
Run the following:
export DOCKER_HOST="tcp://192.168.99.100:4243" # your Docker virtual machine IP may vary
```

The Docker host reported by `docker-machine ip whisk` will give you the IP address.
Currently, the system requires that you use port `4243` to communicate with the Docker host
from OpenWhisk.

Ignore errors messages from `docker-machine ls` for the `whisk` virtual machine, this is due
to the configuration of the port `4243` vs. `2376`
```
NAME      ACTIVE   DRIVER       STATE     URL                         SWARM   DOCKER    ERRORS
whisk     -        virtualbox   Running   tcp://192.168.99.100:2376           Unknown   Unable to query docker version: Cannot connect to the docker engine endpoint
```

To verify that docker is configure properly with `docker-machine` run `docker ps`, you should not see any errors. Here is an example output:
```
CONTAINER ID        IMAGE               COMMAND                  CREATED             STATUS              PORTS                    NAMES

```

You may find it convenient to set these environment variables in your bash profile (e.g., `~/.bash_profile` or `~/.profile`).
```
export OPENWHISK_HOME=/your/path/to/openwhisk
export DOCKER_HOST=tcp://$(docker-machine ip whisk):4243
```

The tweaks to the Docker machine persist across reboots.
However one of the tweaks is applied on the Mac host and must be applied
again if you reboot your Mac. Without it, some tests which require direct
communication with Docker containers will fail. To run just the Mac host tweaks,
run the following [script](./tweak-dockerhost.sh). Enter your sudo Mac password when prompted.
```
cd /your/path/to/openwhisk
./tools/macos/tweak-dockerhost.sh
```

# Build
```
cd /your/path/to/openwhisk
./gradlew distDocker
```
**Tip** Using `gradlew` handles the installation of the correct version of gradle to use.

# Deploy
Follow instructions in [ansible/README.md](../../ansible/README.md)

### Configure the CLI
Follow instructions in [Configure CLI](../../docs/README.md#setting-up-the-openwhisk-cli)

### Use the wsk CLI
```
bin/wsk action invoke /whisk.system/utils/echo -p message hello --result
{
    "message": "hello"
}
```

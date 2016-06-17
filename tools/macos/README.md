# How to setup a Mac host for OpenWhisk

One way to develop or deploy OpenWhisk on a Mac is to use [docker-machine](https://docs.docker.com/machine/install-machine/) which runs the Docker daemon inside a virtual machine accessible from the Mac host.

# Prerequisites

The following are required to build OpenWhisk. The easiest way to install the following is using [Homebrew](http://brew.sh/) with [Cask](https://github.com/caskroom/homebrew-cask). You can copy the entire section below and paste into your terminal to run all of the steps required.

```
echo '
# install homebrew
/usr/bin/ruby -e "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/master/install)"
# install cask
brew tap caskroom/cask
# install virtualbox
brew cask install virtualbox
# install docker
brew install https://raw.githubusercontent.com/Homebrew/homebrew-core/e010e91a6bb5ad6697fb158017b36f575bbe644f/Formula/docker.rb
# install docker-machine
brew install docker-machine
# install java 8, scala
brew cask install java
brew install scala
# install ant and gradle
brew install ant
brew install ant-contrib
brew install gradle
# the following is required for running tests
sudo pip install jsonschema' | bash
```

# Create and configure Docker machine

It is recommended that you create a virtual machine with at least 4GB of RAM.

```
docker-machine create -d virtualbox \
   --virtualbox-memory 4096 \
   --virtualbox-boot2docker-url=https://github.com/boot2docker/boot2docker/releases/download/v1.9.1/boot2docker.iso \
    whisk # the name of your docker machine
```

The Docker virtual machine requires some tweaking to work from the Mac host and OpenWhisk.
The following [script](./tweak-dockermachine.sh) will disable TLS, add port forwarding
within the VM and routes `172.17.x.x` form the Mac host to the Docker virtual machine.
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

The Docker host reported by `docker-machine ip` will give you the IP address. You must use port `4243`.
You may find it convenient to set these environment variable in your bash profile.

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

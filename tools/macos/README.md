# Setting up OpenWhisk with Docker for Mac

OpenWhisk can run on a Mac host with [Docker for Mac](https://docs.docker.com/docker-for-mac/).
If you prefer to use Docker-machine, you can follow instructions in [docker-machine/README.md](docker-machine/README.md)

# Prerequisites

The following are required to build and deploy OpenWhisk from a Mac host:

- [Docker 1.12.0](https://docs.docker.com/docker-for-mac/)
- [Java 8](http://www.oracle.com/technetwork/java/javase/downloads/index.html)
- [Scala 2.11](http://scala-lang.org/download/)
- [Ansible 2.3.0.0](http://docs.ansible.com/ansible/intro_installation.html)

**Tip** Versions of Docker and Ansible are lower than the latest released versions, the versions used in OpenWhisk are pinned to have stability during continuous integration and deployment.


[Homebrew](http://brew.sh/) is an easy way to install all of these and prepare your Mac to build and deploy OpenWhisk. The following shell command is provided for your convenience to install `brew` with [Cask](https://github.com/caskroom/homebrew-cask) and bootstraps these to complete the setup. Copy the entire section below and paste it into your terminal to run it.

```
echo '
# install homebrew
/usr/bin/ruby -e "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/master/install)"
# install cask
brew tap caskroom/cask
# install java 8
brew cask install java
# install scala
brew install scala
# install pip
sudo easy_install pip
# install script prerequisites
sudo -H pip install docker==2.2.1 ansible==2.3.0.0 jinja2==2.9.6 couchdb==1.1 httplib2==0.9.2 requests==2.10.0' | bash
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
Follow instructions in [Configure CLI](../../docs/cli.md)

### Use the wsk CLI
```
bin/wsk action invoke /whisk.system/utils/echo -p message hello --result
{
    "message": "hello"
}
```

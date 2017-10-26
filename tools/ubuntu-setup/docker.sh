#!/bin/bash
set -e
set -x

sudo apt-get -y install apt-transport-https ca-certificates

# Docker GPG Key
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo apt-key add -

sudo add-apt-repository \
   "deb [arch=amd64] https://download.docker.com/linux/ubuntu \
   $(lsb_release -cs) \
   stable"

sudo apt-get -y update -qq

# AUFS
sudo apt-get -y install linux-image-extra-$(uname -r) linux-image-extra-virtual

# DOCKER
sudo apt-get install -y --force-yes docker-ce

sudo gpasswd -a `whoami` docker

sudo service docker restart

# do not run this command without a vagrant reload during provisioning
# it gives an error that docker is not up (which the reload fixes).
# sudo docker version

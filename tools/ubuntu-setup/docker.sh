#!/bin/bash
set -e
set -x

sudo apt-get -y install apt-transport-https ca-certificates
sudo apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys 36A1D7869245C8950F966E92D8576A8BA88D21E9
sudo sh -c "echo deb https://apt.dockerproject.org/repo ubuntu-trusty main  > /etc/apt/sources.list.d/docker.list"
sudo apt-get -y update -qq

sudo apt-get purge lxc-docker
sudo apt-cache policy docker-engine

# AUFS
sudo apt-get -y install linux-image-extra-$(uname -r)

# DOCKER
sudo apt-get install -y --force-yes docker-engine=1.12.0-0~trusty

# enable (security - use 127.0.0.1)
sudo -E bash -c 'echo '\''DOCKER_OPTS="-H tcp://0.0.0.0:4243 -H unix:///var/run/docker.sock --storage-driver=aufs"'\'' >> /etc/default/docker'
sudo gpasswd -a `whoami` docker

# Set DOCKER_HOST as an environment variable
sudo -E bash -c 'echo '\''export DOCKER_HOST="tcp://0.0.0.0:4243"'\'' >> /etc/bash.bashrc'
source /etc/bash.bashrc

sudo service docker restart

# do not run this command without a vagrant reload during provisioning
# it gives an error that docker is not up (which the reload fixes).
# sudo docker version

#!/bin/bash

set -ex

sudo apt-get -y install apt-transport-https
sudo apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys 36A1D7869245C8950F966E92D8576A8BA88D21E9
sudo sh -c "echo deb https://get.docker.io/ubuntu docker main > /etc/apt/sources.list.d/docker.list"
sudo apt-get -y update -qq

# AUFS
sudo apt-get -y install linux-image-extra-$(uname -r)

# DOCKER
sudo apt-get install -q -y --force-yes lxc-docker-1.9.0

# enable (security - use 127.0.0.1)
#echo 'DOCKER_OPTS="-H tcp://0.0.0.0:4243 -H unix:///var/run/docker.sock --api-enable-cors"' >> /etc/default/docker
#echo 'DOCKER_OPTS="-H tcp://127.0.0.1:4243 -H unix:///var/run/docker.sock --api-enable-cors"' >> /etc/default/docker
#echo 'DOCKER_OPTS="-H tcp://0.0.0.0:4243 -H unix:///var/run/docker.sock --api-enable-cors"' >> /etc/default/docker
#echo 'DOCKER_OPTS="-H tcp://172.17.42.1:4243 --api-enable-cors"' >> /etc/default/docker
#echo 'DOCKER_OPTS="-H tcp://172.17.42.1:4243 -H unix:///var/run/docker.sock --api-enable-cors"' >> /etc/default/docker
#echo 'DOCKER_OPTS="-H tcp://0.0.0.0:4243 -H unix:///var/run/docker.sock --api-enable-cors --storage-driver=aufs"' >> /etc/default/docker
sudo -E bash -c 'echo '\''DOCKER_OPTS="-H tcp://0.0.0.0:4243 -H unix:///var/run/docker.sock --api-enable-cors --storage-driver=aufs"'\'' >> /etc/default/docker'

sudo gpasswd -a vagrant docker

sudo service docker restart

# do not run this command without a vagrant reload during provisioning
# it gives an error that docker is not up (which the reload fixes).
# sudo docker version

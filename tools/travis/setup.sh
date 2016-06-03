#!/bin/bash

# Docker stuff
sudo apt-get -y update -qq
sudo apt-get -o Dpkg::Options::="--force-confnew" --force-yes -y install docker-engine=1.9.1-0~trusty
docker --version

# Setup docker
sudo -E bash -c 'echo '\''DOCKER_OPTS="-H tcp://0.0.0.0:4243 -H unix:///var/run/docker.sock --api-enable-cors --storage-driver=aufs"'\'' >> /etc/default/docker'
sudo gpasswd -a travis docker
sudo service docker restart

# Python stuff
sudo apt-get -y install python-pip
pip install --user jsonschema
pip install --user ansible

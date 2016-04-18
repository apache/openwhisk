#!/bin/bash

# Setup docker
sudo -E bash -c 'echo '\''DOCKER_OPTS="-H tcp://0.0.0.0:4243 -H unix:///var/run/docker.sock --api-enable-cors --storage-driver=aufs"'\'' >> /etc/default/docker'
sudo gpasswd -a travis docker
sudo service docker restart




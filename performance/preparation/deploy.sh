#!/bin/sh

set -e
currentDir="$(cd "$(dirname "$0")"; pwd)"

# common docker setup
sudo gpasswd -a travis docker
sudo -E bash -c 'echo '\''DOCKER_OPTS="-H tcp://0.0.0.0:4243 -H unix:///var/run/docker.sock --storage-driver=overlay --userns-remap=default"'\'' > /etc/default/docker'
sudo service docker restart

# install ansible
pip install --user ansible==2.4.2.0

cd $currentDir/../../ansible
ANSIBLE_CMD="ansible-playbook -i environments/local -e docker_image_prefix=openwhisk -e docker_registry=docker.io/ -e limit_invocations_per_minute=999999 -e limit_invocations_concurrent=999999 -e limit_invocations_concurrent_system=999999"

$ANSIBLE_CMD setup.yml
$ANSIBLE_CMD prereq.yml
$ANSIBLE_CMD couchdb.yml
$ANSIBLE_CMD initdb.yml
$ANSIBLE_CMD wipe.yml

$ANSIBLE_CMD kafka.yml
$ANSIBLE_CMD controller.yml
$ANSIBLE_CMD invoker.yml

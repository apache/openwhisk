#!/bin/bash
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

# Setup docker-machine VM and OSX host.
# Assumes there exists a docker-machine called whisk; to create one:
# > docker-machine create whisk --driver virtualbox

# Set this to the name of the docker-machine VM.
MACHINE_NAME=${1:-whisk}


# Disable TLS.
docker-machine ssh $MACHINE_NAME "echo DOCKER_TLS=no |sudo tee -a /var/lib/boot2docker/profile > /dev/null"
docker-machine ssh $MACHINE_NAME "echo DOCKER_HOST=\'-H tcp://0.0.0.0:4243\' |sudo tee -a /var/lib/boot2docker/profile > /dev/null"
docker-machine ssh $MACHINE_NAME "echo EXTRA_ARGS=\'--userns-remap=default\' |sudo tee -a /var/lib/boot2docker/profile > /dev/null"
docker-machine ssh $MACHINE_NAME "echo '#!/bin/sh
/sbin/syslogd
STATUS=\$(curl -s -o /dev/null -w '%{http_code}' repo.tinycorelinux.net)
if [ \$STATUS -ne 200 ]; then
    sudo echo \"http://ftp.nluug.nl/os/Linux/distr/tinycorelinux/\" > /opt/tcemirror
fi
su - docker -c \"tce-load -wi python\"
if ! [ -x /usr/local/bin/pip ]; then
    curl -k https://bootstrap.pypa.io/get-pip.py | sudo python
    sudo pip install \"docker==2.2.1\"
    sudo pip install \"httplib2==0.9.2\"
fi
' | sudo tee /var/lib/boot2docker/bootsync.sh > /dev/null"
docker-machine ssh $MACHINE_NAME "sudo chmod +x /var/lib/boot2docker/bootsync.sh"


# Install prereqs
docker-machine ssh $MACHINE_NAME "sudo /var/lib/boot2docker/bootsync.sh > /dev/null"

# Restart docker daemon.
docker-machine ssh $MACHINE_NAME "sudo /etc/init.d/docker restart"

# Set routes on host.
# If you notice the route forwarding is immediately deleted after this script
# runs (you can check by running 'route monitor') then shut down your networking
# (turn off wifi and disconnect all networking cables), wait a few secs and try
# again; you should see the route stick and now you can reenable networking.
echo "Adding route forwarding on your host machine, enter sudo password if/when prompted"
MACHINE_VM_IP=$(docker-machine ip $MACHINE_NAME)
sudo route -n -q delete 172.17.0.0/16
sudo route -n -q add 172.17.0.0/16 $MACHINE_VM_IP
netstat -nr |grep 172\.17

# Env variables to set.
# Note, the user CAN NOT do eval $(docker-machine env whisk) because of a bug in docker-machine that assumes TLS even if not enabled
echo Save the following to your shell profile:
echo "  " export DOCKER_HOST="tcp://$MACHINE_VM_IP:4243"

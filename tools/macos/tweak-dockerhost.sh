#!/bin/bash

# this should run to permit some unit tests to run which directly communicate
# with containers; if you notice the route forwarding is immediately deleted
# after this script runs (you can check by running 'route monitor') then
# shut down your wireless and disconnect all networking cables, wait a few secs
# and try again; you should see the route stick and now you can re-enable wifi etc.

# Set this to the name of the docker-machine VM.
MACHINE_NAME=whisk
MACHINE_VM_IP=$(docker-machine ip $MACHINE_NAME)

sudo route -n delete 172.17.0.0/16
sudo route -n -q add 172.17.0.0/16 $MACHINE_VM_IP
netstat -nr |grep 172\.17

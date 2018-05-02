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

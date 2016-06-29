#!/bin/bash

#
# Copyright 2015-2016 IBM Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

SCRIPTDIR="$(cd $(dirname "$0")/ && pwd)"
WHISKPROPS=$1
: ${WHISKPROPS:?"WHISKPROPS must be set and non-empty"}

DATABASE_EXEMPT=$2

#
# run cleanDocker.sh on each docker endpoint
#
ALL_HOSTS=`"$SCRIPTDIR/listAllDockerHosts.sh" "$WHISKPROPS"`
DEFAULT_DOCKER_PORT=4243

for h in $ALL_HOSTS
do
    echo "Cleaning docker at $h:${DOCKER_PORT:-$DEFAULT_DOCKER_PORT}"
    DOCKER_ENDPOINT=$h:${DOCKER_PORT:-$DEFAULT_DOCKER_PORT} "$SCRIPTDIR/cleanDocker.sh" "$DATABASE_EXEMPT" &
done
wait

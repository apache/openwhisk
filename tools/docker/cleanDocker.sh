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

DATABASE_EXEMPT=$1

# Kill all running containers and remove all dangling (non-leaf) images
# Add "--purge" to remove all images
if [ -n "${DOCKER_ENDPOINT+1}" ]; then
   DOCKER="docker --host tcp://$DOCKER_ENDPOINT"
else
   DOCKER="docker"
fi
if [ -n $DOCKER_TLS_CMD ]; then
   DOCKER="$DOCKER $DOCKER_TLS_CMD"
fi

# First stop and rm all containers before dealing with images.
#
# We are using kill instead of stop deliberately, since stop is too slow
# during development.   Unfriendly?   Too bad.
#
# Container exempted from death:
# whisk_docker_registry
# whiskrouter
# *catalog*
# *swarm*
#

if [ "$DATABASE_EXEMPT" == "couchdb" ];
then
    RUNNING=$($DOCKER ps -a | fgrep -v "whisk_docker_registry" | fgrep -v "whiskrouter" | fgrep -v "catalog" | fgrep -v "swarm" | fgrep -v "couchdb" | sed '1d' | awk '{print $1}')
else
    RUNNING=$($DOCKER ps -a | fgrep -v "whisk_docker_registry" | fgrep -v "whiskrouter" | fgrep -v "catalog" | fgrep -v "swarm" | sed '1d' | awk '{print $1}')
fi

if [ -n "$RUNNING" ]
then
   $DOCKER unpause $RUNNING > /dev/null
   $DOCKER kill $RUNNING
   $DOCKER rm -f -v $RUNNING
fi

# Dangling means non-leaf/intermediates images created during construction
DANGLING=$($DOCKER images -q -f dangling=true)
if [ -n "$DANGLING" ]
then
	$DOCKER rmi -f $DANGLING
fi

# remove all images tagged with the string :5000/whisk for pushing to local docker registry.   This excludes the catalog images
#TAGGED=$($DOCKER images | fgrep ":5000/whisk" | awk '{print $3}')
#if [ -n "$TAGGED" ]
#then
#	$DOCKER rmi -f $TAGGED
#fi

ALL=$($DOCKER images -q)
if [[ "$1" = "--purge" && -n "$ALL" ]] ; then
	$DOCKER rmi $ALL
fi


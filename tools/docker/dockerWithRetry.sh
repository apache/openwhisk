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

#
# Usage: runDocker timeOutInSec docker_args ....
# Example:  runDocker 50 build ...
# 
# This script will run the docker command up to TRYCOUNT times until it finishes _CORRECTLY_ within the time limit.
# Each time it does not finish within the timeout or finishes with a non-zero status, 
# the process is kill-ed -9 (if needed), waits for a while, and then tried again.
#
# Upon success, the return value of the succeeding docker command.
# Upon failure, the return value of the failing docker command is returned. 
#   In case of timeout, 222 is returned.
#
# NOTE: After killing the docker command, we wait 2 min so that the lock on a killed push can expire.
#       This seems to be a known bug for Docker pushes.

# The total number of times we will run the docker operation for.
TRYCOUNT=5

# After this, $@ will have just the docker arguments
TIMEOUT=$1
shift
DOCKER_ARGS=()
# wrap args with quotes to handle spaces
for arg in "$@"
do
  DOCKER_ARGS="$DOCKER_ARGS \"$arg\""
done
# Wait this long for the effects of the "kill -9" operation to take effect - underlying docker lock timing out
# This is not the timeout for the docker operation which is passed in as an argument.
WAIT_FOR=60
# wait this long in between checking for docker completion
IDLE_FOR=5

# This will run the docker command once, exiting the script if it finished within the time limit
STATUS=-1
runOnce() {
    date
    echo "With timeout $TIMEOUT, running:  docker $DOCKER_ARGS"
    eval docker $DOCKER_ARGS &
    DOCKER_PID=$!
    echo "docker process has pid $DOCKER_PID"
    REMAINING_TIME=TIMEOUT
    # Note the grep -v grep is needed on Mac as BSD ps (unlike linux) will display command arguments even when brief.
    while ps -p $DOCKER_PID | grep "$DOCKER_PID " | grep -v grep
    do
        echo $DOCKER_PID is still in the ps output. Must still be running.
        sleep $IDLE_FOR
        let REMAINING_TIME=REMAINING_TIME-IDLE_FOR
        if [ $REMAINING_TIME -lt 0 ]
        then
	        echo "Docker did not finish in time - killing -9 $DOCKER_PID"
            kill -9 $DOCKER_PID
            STATUS=222
	        echo "Sleeping for $WAIT_FOR seconds to allow recovery"
            sleep $WAIT_FOR
            echo "Done sleeping"
            return
        fi
    done
    # If we get here, the process exited in time.
    wait $DOCKER_PID
    STATUS=$?
    echo "Docker process $DOCKER_PID finished in time with status $STATUS."
    if [ $STATUS -ne 0 ]
    then
        echo "Sleeping for $WAIT_FOR seconds to allow recovery (even though it wasn't killed)"
        sleep $WAIT_FOR
        echo "Done sleeping"
        return
    fi
    echo "dockerWithRetry exiting with 0"
    exit 0
}

# We'll try twice.
while [  $TRYCOUNT -gt 0 ]; do
    runOnce
    let TRYCOUNT=TRYCOUNT-1
done
echo "dockerWithRetry exiting with $STATUS"
exit $STATUS

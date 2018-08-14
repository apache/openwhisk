#!/bin/bash
# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

set -ex

# Run the custom task mounted into /task by kube/docker
if [ -f /task/myTask.sh ]; then
    . /task/myTask.sh
else
    echo "I was not given a task to execute.  Exiting with error"
    exit 1
fi

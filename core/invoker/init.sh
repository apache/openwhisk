#!/bin/bash

# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

./copyJMXFiles.sh

export INVOKER_OPTS
INVOKER_OPTS="$INVOKER_OPTS $(./transformEnvironment.sh)"

exec invoker/bin/invoker "$@"

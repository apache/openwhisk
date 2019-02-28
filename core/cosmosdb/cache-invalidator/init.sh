#!/bin/bash

# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

./copyJMXFiles.sh

export CACHE_INVALIDATOR_OPTS
CACHE_INVALIDATOR_OPTS="$CACHE_INVALIDATOR_OPTS $(./transformEnvironment.sh)"

exec cache-invalidator/bin/cache-invalidator "$@"

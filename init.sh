#!/bin/bash

# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

export USER_METRICS_OPTS
USER_METRICS_OPTS="$USER_METRICS_OPTS $(./transformEnvironment.sh)"

exec user-metrics/bin/user-metrics "$@"

#!/bin/bash

# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

./copyJMXFiles.sh

export LAMBDA_BUILDER_OPTS
LAMBDA_BUILDER_OPTS="$LAMBDA_BUILDER_OPTS $(./transformEnvironment.sh)"

exec lambda-builder/bin/lambda-builder "$@"

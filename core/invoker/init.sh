#!/bin/bash

./copyJMXFiles.sh

export INVOKER_OPTS
INVOKER_OPTS="$INVOKER_OPTS $(./transformEnvironment.sh)"

exec invoker/bin/invoker "$@"

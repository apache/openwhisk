#!/bin/bash

./copyJMXFiles.sh

export CONTROLLER_OPTS
CONTROLLER_OPTS="$CONTROLLER_OPTS -Dakka.remote.netty.tcp.bind-hostname=$(hostname -i) $(./transformEnvironment.sh)"

exec controller/bin/controller "$@"

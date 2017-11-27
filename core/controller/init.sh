#!/bin/bash

export CONTROLLER_OPTS
CONTROLLER_OPTS="$CONTROLLER_OPTS -Dakka.remote.netty.tcp.bind-hostname=$(hostname -I) $(./transformEnvironment.sh)"

exec controller/bin/controller "$@"
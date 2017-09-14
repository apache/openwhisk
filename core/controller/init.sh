#!/bin/bash

if [[ $( ls /logs/jmxremote.* 2> /dev/null ) ]]
then
  mv /logs/jmxremote.* /root ; chmod 600 /root/jmxremote.*
fi

export CONTROLLER_OPTS
CONTROLLER_OPTS="$CONTROLLER_OPTS -Dakka.remote.netty.tcp.bind-hostname=$(hostname -I) $(./transformEnvironment.sh)"

exec controller/bin/controller "$@"

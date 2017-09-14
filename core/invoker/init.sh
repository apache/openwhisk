#!/bin/bash

if [[ $( ls /logs/jmxremote.* 2> /dev/null ) ]]
then
  mv /logs/jmxremote.* /root ; chmod 600 /root/jmxremote.*
fi

export INVOKER_OPTS
INVOKER_OPTS="$INVOKER_OPTS $(./transformEnvironment.sh)"

exec invoker/bin/invoker "$@"

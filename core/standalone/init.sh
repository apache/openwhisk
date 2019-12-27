#!/bin/bash
if ! test -e /var/run/docker.sock
then echo "Please launch this image with the option -v /var/run/docker.sock:/var/run/docker.sock"
     exit 1
fi
java \
  -Dwhisk.standalone.host.name="$(hostname)"\
  -Dwhisk.standalone.host.internal="$(hostname -I)"\
  -Dwhisk.standalone.host.external=localhost\
  -jar openwhisk-standalone.jar "$@"
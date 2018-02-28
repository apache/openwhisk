#!/bin/bash

#  This script can be tested for validity by doing something like:
#
#  docker run -v "${OPENWHISK_HOME}:/openwhisk" ubuntu:trusty \
#    sh -c 'apt-get update && apt-get -y install sudo && /openwhisk/tools/ubuntu-setup/all.sh'
#
#  ...but see the WARNING at the bottom of the script before tinkering.

set -e
set -x
SOURCE="${BASH_SOURCE[0]}"
SCRIPTDIR="$( dirname "$SOURCE" )"

echo "*** installing basics"
/bin/bash "$SCRIPTDIR/misc.sh"

echo "*** installing python dependences"
/bin/bash "$SCRIPTDIR/pip.sh"

echo "*** installing java"
/bin/bash "$SCRIPTDIR/java8.sh"

echo "*** installing ansible"
/bin/bash "$SCRIPTDIR/ansible.sh"

# WARNING:
#
# This step MUST be last when testing scripts for validity using
# Docker (as recommended above).  The reason is because the scripted restart
# of docker may actually communicates with a Docker for Mac controlling
# instance and terminate the container.  It's the last step, so it's okay,
# but nothing after this step will run in that validity test situation.

echo "*** installing docker"
u_release="$(lsb_release -rs)"
if [ "${u_release%%.*}" -lt "16" ]; then
    /bin/bash "$SCRIPTDIR/docker.sh"
else
    echo "--- WARNING -------------------------------------------------"
    echo "Using EXPERIMENTAL Docker CE script on Xenial or later Ubuntu"
    echo "--- WARNING -------------------------------------------------"
    /bin/bash "$SCRIPTDIR/docker-xenial.sh"
fi

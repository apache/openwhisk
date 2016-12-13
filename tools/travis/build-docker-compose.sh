#!/bin/bash

SCRIPTDIR=$(cd $(dirname "$0") && pwd)
ROOTDIR="$SCRIPTDIR/../.."

cd $ROOTDIR
cd ./tools/docker-compose/ && PATH=$PATH:/usr/local/bin/ make quick-start stop

#!/usr/bin/env bash

#
# This script wraps the default one that comes with couchdb (docker-entrypoint.sh)
# It starts an import process in the background executing /import.sh which waits for the DB to become ready
# and then it imports data from the /import folder. For more details see the file import.sh
#

if [ "$1" = 'couchdb' ]; then
    #1. prepare import
    sh +x /import.sh 2>&1 /dev/stderr &
    #2. start database
    /docker-entrypoint.sh "$@"
else
    exec "$@"
fi





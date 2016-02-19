#!/bin/bash
#
# Write a master properties file for deployment
#


if [[ $# -eq 0 ]]; then
    echo "error: a file name is required; it defines the environment properties to source e.g., localEnv.sh"
    exit 1
fi

# quote the path to guard against a space in path
# which will silently fail to source ($? == 0)
source "$1"
rc=$?; if [[ $rc != 0 ]]; then exit $rc; fi

source ./writePropertyFile.sh
rc=$?; if [[ $rc != 0 ]]; then exit $rc; fi

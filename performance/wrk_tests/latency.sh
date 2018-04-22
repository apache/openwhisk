#!/bin/sh
set -e
currentDir="$(cd "$(dirname "$0")"; pwd)"

# Host to use. Needs to include the protocol.
host=$1
# Credentials to use for the test. USER:PASS format.
credentials=$2
# How long to run the test
duration=${3:-30s}

$currentDir/throughput.sh $host $credentials 1 1 $duration

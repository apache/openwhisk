#!/bin/sh
set -e

# Host to use. Needs to include the protocol.
host=$1
# Credentials to use for the test. USER:PASS format.
credentials=$2
# Name of the action to create and test.
action=$3

# create a noop action
echo "Creating action $action"
curl -k -u "$credentials" "$host/api/v1/namespaces/_/actions/$action" -XPUT -d '{"namespace":"_","name":"test","exec":{"kind":"nodejs:default","code":"function main(){return {};}"}}' -H "Content-Type: application/json"

# run the noop action
echo "Running $action once to assert an intact system"
curl -k -u "$credentials" "$host/api/v1/namespaces/_/actions/$action?blocking=true" -XPOST

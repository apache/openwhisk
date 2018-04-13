#!/bin/sh

set -e
SCRIPTDIR="$(cd "$(dirname "$0")"; pwd)"
ROOTDIR="$SCRIPTDIR/../.."

# Build Openwhisk
cd $ROOTDIR
TERM=dumb ./gradlew distDocker -PdockerImagePrefix=testing $GRADLE_PROJS_SKIP

# Deploy Openwhisk
cd $ROOTDIR/ansible
ANSIBLE_CMD="$ANSIBLE_CMD -e limit_invocations_per_minute=999999 -e limit_invocations_concurrent=999999 -e limit_invocations_concurrent_system=999999 -e controller_client_auth=false"

$ANSIBLE_CMD setup.yml
$ANSIBLE_CMD prereq.yml
$ANSIBLE_CMD couchdb.yml
$ANSIBLE_CMD initdb.yml
$ANSIBLE_CMD wipe.yml

$ANSIBLE_CMD kafka.yml
$ANSIBLE_CMD controller.yml
$ANSIBLE_CMD invoker.yml
$ANSIBLE_CMD edge.yml

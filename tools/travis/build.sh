#!/bin/bash
set -e

# Build script for Travis-CI.

SCRIPTDIR=$(cd $(dirname "$0") && pwd)
ROOTDIR="$SCRIPTDIR/../.."

cd $ROOTDIR
tools/build/scanCode.py .

cd $ROOTDIR/ansible

ANSIBLE_CMD="ansible-playbook -i environments/local -e docker_image_prefix=testing"

$ANSIBLE_CMD setup.yml
$ANSIBLE_CMD prereq.yml
$ANSIBLE_CMD couchdb.yml
$ANSIBLE_CMD initdb.yml
$ANSIBLE_CMD apigateway.yml

cd $ROOTDIR

./gradlew distDocker -x :core:swiftAction:distDocker -x :core:swift3Action:distDocker -x :core:pythonAction:distDocker -PdockerImagePrefix=testing

cd $ROOTDIR/ansible

$ANSIBLE_CMD wipe.yml
$ANSIBLE_CMD openwhisk.yml

cd $ROOTDIR
cat whisk.properties
./gradlew :tests:testLean

cd $ROOTDIR/ansible
$ANSIBLE_CMD logs.yml

cd $ROOTDIR
tools/build/checkLogs.py logs
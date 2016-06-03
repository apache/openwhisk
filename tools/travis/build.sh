#!/bin/bash
set -e

# Build script for Travis-CI.

SCRIPTDIR=$(cd $(dirname "$0") && pwd)
ROOTDIR="$SCRIPTDIR/../.."

cd $ROOTDIR/ansible

ANSIBLE_CMD="ansible-playbook -i environments/travis"

$ANSIBLE_CMD prereq.yml
$ANSIBLE_CMD couchdb.yml
$ANSIBLE_CMD couchdb.yml -e mode=initdb -e prompt_user=false

cd $ROOTDIR

./gradlew distDocker

cd $ROOTDIR/ansible

$ANSIBLE_CMD openwhisk.yml

cd $ROOTDIR

ant run -Dtestsfailonfailure=true

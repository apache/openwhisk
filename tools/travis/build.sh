#!/bin/bash
set -e

# Build script for Travis-CI.

SCRIPTDIR=$(cd $(dirname "$0") && pwd)
ROOTDIR="$SCRIPTDIR/../.."
HOMEDIR="$SCRIPTDIR/../../../"

# clone the openwhisk utilities repo.
cd $HOMEDIR
git clone https://github.com/apache/incubator-openwhisk-utilities.git

# run the scancode util. against project source code starting at its root
incubator-openwhisk-utilities/scancode/scanCode.py $ROOTDIR --config $ROOTDIR/tools/build/scanCode.cfg

# run scalafmt checks
cd $ROOTDIR
TERM=dumb ./gradlew checkScalafmtAll

cd $ROOTDIR/ansible

ANSIBLE_CMD="ansible-playbook -i environments/local -e docker_image_prefix=testing"
GRADLE_PROJS_SKIP="-x :actionRuntimes:pythonAction:distDocker  -x :actionRuntimes:python2Action:distDocker -x :actionRuntimes:swift3Action:distDocker -x actionRuntimes:swift3.1.1Action:distDocker -x :actionRuntimes:javaAction:distDocker"

$ANSIBLE_CMD setup.yml
$ANSIBLE_CMD prereq.yml
$ANSIBLE_CMD couchdb.yml
$ANSIBLE_CMD initdb.yml
$ANSIBLE_CMD apigateway.yml

cd $ROOTDIR

TERM=dumb ./gradlew distDocker -PdockerImagePrefix=testing $GRADLE_PROJS_SKIP

cd $ROOTDIR/ansible

$ANSIBLE_CMD wipe.yml
$ANSIBLE_CMD openwhisk.yml

cd $ROOTDIR
cat whisk.properties
TERM=dumb ./gradlew :tests:testLean $GRADLE_PROJS_SKIP

cd $ROOTDIR/ansible
$ANSIBLE_CMD logs.yml

cd $ROOTDIR
tools/build/checkLogs.py logs

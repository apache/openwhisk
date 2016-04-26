#!/bin/bash
set -e

# Build script for Travis-CI.

SCRIPTDIR=$(cd $(dirname "$0") && pwd)
ROOTDIR="$SCRIPTDIR/../.."

cd $ROOTDIR

# disable ansible deployment for now. to be enabled later.
#  - ansible-playbook -i ansible/environments/travis ansible/whisk.yml -e mode=initdb
./tools/db/couchdb/start-couchdb-box.sh whisk_couchdb_admin some_gener1c_passw0rd
./tools/db/createImmortalDBs.sh --dropit
ant build
#  - ansible-playbook -i ansible/environments/travis ansible/whisk.yml
ant deploy
ant run -Dtestsfailonfailure=true




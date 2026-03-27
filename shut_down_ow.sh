#!/bin/bash
set -euo pipefail

# This script may require a clean wipe of the DB so be careful!!!

# Set env vars
set -a
source .env
set +a

cd ansible
ansible-playbook -i environments/$ENVIRONMENT openwhisk.yml -e mode=clean
ansible-playbook -i environments/$ENVIRONMENT couchdb.yml -e mode=clean
docker stop etcd0 scheduler0 # for some reason these aren't stopped by the earlier commands
cd ..
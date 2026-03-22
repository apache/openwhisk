#!/bin/bash
set -euo pipefail

# Set env vars
set -a
source .env
set +a

# Rebuilds docker containers (maybe make this optional?)
./gradlew distDocker

# Restarts from scratch, maybe make these steps optional?
cd ansible
ansible-playbook -i environments/$ENVIRONMENT couchdb.yml
ansible-playbook -i environments/$ENVIRONMENT initdb.yml
ansible-playbook -i environments/$ENVIRONMENT wipe.yml
ansible-playbook -i environments/$ENVIRONMENT openwhisk.yml

# Registers toy command for convenience
wsk -i action create fib_wasm wasm_programs/fib.wasm --kind wasm:wasmtime --main fib
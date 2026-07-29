### Start up OpenWhisk

```
# in project root
./gradlew distDocker # builds docker containers

cd ansible
ansible-playbook -i environments/$ENVIRONMENT couchdb.yml
ansible-playbook -i environments/$ENVIRONMENT initdb.yml
ansible-playbook -i environments/$ENVIRONMENT wipe.yml # wipes DB, BE CAREFUL!!!
ansible-playbook -i environments/$ENVIRONMENT openwhisk.yml

# Test using wsk CLI tool
# For many commands you may have to use the -i flag unless you set up certs
wsk property set --apihost 127.0.0.1
wsk property set --auth '23bc46b1-71f6-4ed5-8c54-816aa4f8c502:123zO3xZCLrMN6v2BKK1dXYFpXlPkccOFqm12CdAsMgRU4VrNZ9lyGVCGuMDGIwP' # Default

# Register an action
wsk -i action create hello programs/hello.js --kind nodejs:default

# Run that action
# -r gets the result since actions are invoked asynchronously
wsk -i action invoke hello -r
```

### CouchDB
You can navigate CouchDB using cURL commands, the web UI at (`http://127.0.0.1:5984/_utils`), or the CouchDB python client.

### Using with WASM

```
# Create fib action
# when compiling from Rust, "main" gets renamed to "_start"
wsk -i action create fib_wasm wasm_programs/fib.wasm --kind wasm:wasmtime --main _start
```

### Developing

```
# Rebuilding a particular container
./gradlew :core:invoker:distDocker

# Re-deploying a particular component
ansible-playbook -i environments/$ENVIRONMENT invoker.yml

# Compiling a rs file with wasmtime
rustc --target wasm32-wasip1 -O "wasm_programs/fib.rs" -o "wasm_programs/fib.wasm"
```
#!/bin/bash
set -x
#routerurl=$(minikube service ow-router-service --url)
routerurl="http://localhost:8080"

for i in $(seq 1 1); do
    curl -X POST ${routerurl}/namespaces/bladerun_test/actions/test${i}    
done

#for i in $(seq 1 5); do
#    curl -X POST ${routerurl}/namespaces/bladerun_test/actions/test${i}    
#done

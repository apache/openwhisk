# Building

## Locally
You'll need go installed version 1.12 seems to work.

`make router`

This will give you `./bin/router`

The binary can be run from outside the cluster by specifying the --gateway parameter as the knative gateway that can be reached from your host where you run router. 

## Container

`make container` will build the router inside a container so you don't even need go installed. It will also build scratch based container to run the router in.

`make deploy-container` pushes the container to artifactory


# Deploy

#Local Run

## Run etcd local for testing

```bash
export DATA_DIR="etcd-data"
#use your local ip - see TODOs
export NODE1=$(ip route get 8.8.8.8 | awk -F"src " 'NR==1{split($2,a," ");print a[1]}')
docker volume create --name etcd-data
docker run   -p 2379:2379   -p 2380:2380   --volume=${DATA_DIR}:/etcd-data   --name etcd gcr.io/etcd-development/etcd:latest   /usr/local/bin/etcd   --data-dir=/etcd-data --name node1   --initial-advertise-peer-urls http://${NODE1}:2380 --listen-peer-urls http://0.0.0.0:2380   --advertise-client-urls http://${NODE1}:2379 --listen-client-urls http://0.0.0.0:2379   --initial-cluster node1=http://${NODE1}:2380
```

You can run the router locally if you have a kube config around with sufficient permissions: `bin/router --kubeconfig <path_to_config> --etcd <etcd url> --gateway <knative gateway host:port>`

# In Cluster
## Docker Registry
The deployment yaml uses a docker registry credential named: "regcred".
Follow https://kubernetes.io/docs/tasks/configure-pod-container/pull-image-private-registry/#registry-secret-existing-credentials to get that working.

```
kubectl create secret docker-registry regcred --docker-server=docker-bladerunner-snapshot.dr-uw2.adobeitc.com --docker-username=tnorris --docker-password=<artifactory api key> --docker-email=tnorris@adobe.com
kubectl patch serviceaccount default -p '{"imagePullSecrets": [{"name": "regcred"}]}'
```

You can also deploy to a k8s cluster: `make deploy-router`

## etcd in cluster

TODO

# Make it do stuff

# get the ip:
`kubectl get service ow-router-service -o jsonpath={.status.loadBalancer.ingress[0].ip}`

`curl -v -X POST http://<service_external_host_port>/namespaces/<namespace>/actions/<action>`

# Shared Container
If you are going to run in cluster please make sure you pick a different image name or version to use. Right now the YAML is pinned to "latest" from artifactory this is shared across everyone messing with this.

# etcdctl 

## Using etcdctl locally

Note that you need to set `ETCDCTL_API=3` locally to access data managed via clientv3:
```bash
ETCDCTL_API=3 etcdctl  --endpoints=<etcdurl> get /openwhisk/ --prefix
```

# Known Issues

[X] - don't poll the api server

[X] - when sending the first request the dns lookup fails because the dns hasn't propagated to the router yet

[ ] - forwarded requests are REALLY slow.

[ ] - run etcd in k8s cluster
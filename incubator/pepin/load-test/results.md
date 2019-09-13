# Setup
## Host Machine

`sudo sysctl net.ipv4.tcp_tw_reuse=1`
Make sure you upload docker creds for the internal registries:
`kubectl create secret generic regcred --from-file=.dockerconfigjson=${HOME}/.docker/config.json --type=kubernetes.io/dockerconfigjson`
`kubectl patch serviceaccount default -p '{"imagePullSecrets": [{"name": "regcred"}]}'`

## Minikube
```
minikube start --memory=8192 --cpus=6 \
  --kubernetes-version=v1.13.0 --extra-config=apiserver.enable-admission-plugins="LimitRanger,NamespaceExists,NamespaceLifecycle,ResourceQuota,ServiceAccount,DefaultStorageClass,MutatingAdmissionWebhook"
```

## KNative
```
glooctl community edition version 0.17.6
```

# Load Tests
All tests last 4 mins:
1. 0-1 min ramp 0-10 users
2. 1-2 min ramp 10-20 users
3. 2-3 mins hold at 20 users
4. 3-4 mins ramp to 0 users

Each user just makes a single request to the service

The tests are written with [k6](https://github.com/loadimpact/k6). 


## Raw Echo Container Performance
A test for the container that backs all the services ( besides the router ). This attempts to establish the overall maximum performance we can expect. This doesn't even run inside k8s just all bare metal on a very powerful machine.

*Note this test does not send data to influx and therefore may perform slightly better than the above.*
Example command:
`k6 run --discard-response-bodies ./echo-test.js`

## K8s Service
A simple service backed by the same gin based http container that the router creates for each "action."

Example command:

`k6 run -e TARGET=$(minikube service ow-loopback-perf-service --url)  --out influxdb=http://coldplay.corp.adobe.com:8086/k6 -e KNATIVE=0 -e KNATIVE_LOOPBACK=0 ./action-test.js`


## OW Router Echo
This doesn't use Knative routing but instead the OW router sends back a string and a 200 status code directly.

Example command:

`k6 run -e TARGET=$(minikube service ow-router-service --url) --out influxdb=http://coldplay.corp.adobe.com:8086/k6 -e KNATIVE=1 -e KNATIVE_LOOPBACK=2 ./action-test.js`


## OW Router to K8s Service
This doesn't use Knative routing but instead the OW router stands up all the same KNative parts but forwards the request to the same K8s Service tested above.

Example command:

`k6 run -e TARGET=$(minikube service ow-router-service --url)  --out influxdb=http://coldplay.corp.adobe.com:8086/k6 -e KNATIVE=1 -e KNATIVE_LOOPBACK=1 ./action-test.js`

## OW Router to Knative Service
This tests the full way through hitting the new OW Router and going to the Knative services it has stood up.

Example command:

`k6 run -e TARGET=$(minikube service ow-router-service --url)  --out influxdb=http://coldplay.corp.adobe.com:8086/k6 -e KNATIVE=1 -e KNATIVE_LOOPBACK=0 ./action-test.js`

## Direct to KNative service
`k6 run -e INGRESS=$(glooctl proxy url --name clusteringress-proxy) -e TARGETS=$(kubectl get ksvc --output=json | jq -r ".items | map(.status.url) | join(\",\")") --out influxdb=http://coldplay.corp.adobe.com:8086/k6 ./knative-service.js`

# Results

## Raw Echo Container Performance
| Run #  | HTTP Req Avg  | HTTP Req p(90)  | HTTP Req p(95)   |
|---|---|---|---|
|  1565284714047 | 370.73µs  | 620.84µs | 856.2µs |
|  1565285192289 | 386.21µs  | 654.29µs | 908.41µs |
|  1565284714047 | 392.17µs  | 670.13µs | 932.24µs |

## K8S Service
| Run #  | HTTP Req Avg  | HTTP Req p(90)  | HTTP Req p(95)   |
|---|---|---|---|
|  1565372001485 |5.64 ms | 10.86 ms  | 13.70 ms  |
|  1565372302198 |5.50 ms  |10.53 ms   | 13.32 ms  |
|  1565372592940 |5.56 ms   | 10.58 ms  | 13.34 ms |

## OW Router Echo
| Run #  | HTTP Req Avg  | HTTP Req p(90)  | HTTP Req p(95)   |
|---|---|---|---|
| 1565368763319 |6.52 ms | 12.58 ms  | 16.06 ms  |
| 1565369073929 |6.91 ms | 13.94 ms|18.31 ms|
| 1565369801625 |6.70 ms | 13.02 ms|16.65 ms|

## OW Router to K8S Service
| Run #  | HTTP Req Avg  | HTTP Req p(90)  | HTTP Req p(95)   |
|---|---|---|---|
|  1565367516418 | 9.32 ms | 	16.97 ms | 21.14 ms |
|  1565367837742 | 9.38 ms | 17.16 ms  | 21.29 ms |
|  1565368138125 | 9.54 ms | 17.98 ms  |22.84 ms  |

## OW Router to Knative Service
| Run #  | HTTP Req Avg  | HTTP Req p(90)  | HTTP Req p(95)   |
|---|---|---|---|
|  1565375143876 | 34.21 ms  | 67.51 ms | 81.72 ms |
|  1565189567000 | 34.99 ms  | 68.20 ms   | 82.55 ms  |
|  1565190197188| 34.65 ms  |  68.61 ms |83.70 ms  |
|  1565378036432 | 35.35 ms  |  70.27 ms |  86.28 ms|

## Direct to Knative
| Run #  | HTTP Req Avg  | HTTP Req p(90)  | HTTP Req p(95)   |
|---|---|---|---|
|  1565372905118 | 28.60 ms  |  	56.85 ms | 71.46 ms|
|  1565373588344 | 28.97 ms  |  	58.94 ms | 	74.94 ms |
|  1565374464382 | 28.96 ms|  	58.27 ms | 	72.19 ms |
|  1565374755233 | 29.46 ms |  	60.23 ms | 76.09 ms |
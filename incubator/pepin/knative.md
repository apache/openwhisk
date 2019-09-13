
# Installing knative on macos

## minikube setup

```bash
# install minikube
brew install minikube

# install hyperkit driver (see https://github.com/kubernetes/minikube/blob/master/docs/drivers.md#hyperkit-driver)
brew install hyperkit
curl -LO https://storage.googleapis.com/minikube/releases/latest/docker-machine-driver-hyperkit \
&& sudo install -o root -g wheel -m 4755 docker-machine-driver-hyperkit /usr/local/bin/

#start a vm using hyperkit driver
minikube start \
  --memory=8192 \
  --cpus=4 \
  --kubernetes-version=v1.15.0 \
  --vm-driver=hyperkit \
  --disk-size=30g \
  --extra-config=apiserver.enable-admission-plugins="LimitRanger,NamespaceExists,NamespaceLifecycle,ResourceQuota,ServiceAccount,DefaultStorageClass,MutatingAdmissionWebhook"

# to access hyperkit vm LoadBalancer from macos:
minikube tunnel
```

## knative setup

Use gloo for now:
* See https://knative.dev/docs/install/knative-with-gloo/

```bash
# install glooctl 
curl -sL https://run.solo.io/gloo/install | sh

# or upgrade glooctl
glooctl upgrade

# install knative + gloo
glooctl install knative --install-knative-version 0.8.0

# install knative client

curl -L -o ~/Downloads/kn https://github.com/knative/client/releases/download/v0.2.0/kn-darwin-amd64
cp ~/Downloads/kn /usr/local/bin/kn

```

## Utilities

```bash
#check knative version
kubectl get deploy -n knative-serving --label-columns=serving.knative.dev/release


```

*  Uninstalling knative

see [https://github.com/knative/serving-operator/issues/59]    



## TODO

    * disable gloo function discovery; is there a better way?:
      ```
      kubectl edit -n gloo-system settings.gloo.solo.io
            fdsMode: DISABLED  
      
      #TODO: is this the right way to disable after gloo is already installed?
      kubectl scale deployment discovery --replicas 0 --namespace gloo-system
      
      ```
    * performance of gloo (envoy?) seems questionable (better for now on hyperkit), or else some better config is missing; some performance discussions at https://github.com/envoyproxy/envoy/issues/5536      
         
# Troubleshooting
   

If you have trouble, 

* try running `glooctl install knative` again, e.g. if you get `error: unable to recognize "STDIN": no matches for kind "Image" in version "caching.internal.knative.dev/v1alpha1"`

* you may need to verify your k8s version is:
    * 1.11 or greater
    * kubectl is no more than one version different

# Using knative

Deploy a sample service:
```bash
kn service create fasthello-go --env TARGET=foo --image tysonnorris/fasthello-go
```

## Sample knative remote invocation:
```bash
export GATEWAY_URL=$(glooctl proxy url --name knative-external-proxy)

curl -H "Host: helloworld-go.default.example.com" $GATEWAY_URL
```

## OW Runtime for Knative

Use the work done by IBM folks for creating a knative service with an OW action-compatible image: 
https://github.com/apache/incubator-openwhisk-runtime-nodejs

If you want to build the image:
```bash
docker build -t openwhisk-nodejs-runtime-knative -f core/nodejs10Action/knative/Dockerfile .
```

* You will need to update `knative/ow-sample/service.yaml` to use your own image.
* TODO: I had to make a minor change to the knative image to make it work - you can use `tysonnorris/openwhisk-nodejs-runtime-knative`

## Sample OW remote invocation:

Deploy a service with the image:
```bash
kn service create nodejs-helloworld --env __OW_RUNTIME_PLATFORM=knative --image tysonnorris/openwhisk-nodejs-runtime-knative
```
Invoke an action with the image:

NOTES: 
* You must pass the body with both init and activation data on all requests.
* Currently you must embed the j
```bash
curl -X POST -H "Content-Type: application/json" -d "@./knative/ow-sample/body.json" -H "Host: nodejs-helloworld.default.example.com" $GATEWAY_URL
```

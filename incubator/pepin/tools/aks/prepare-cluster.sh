#!/bin/bash
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
set -euo pipefail
#set -x
kubectl apply -f ${DIR}/helm-rbac.yaml
helm init --history-max 200 --service-account tiller --node-selectors "beta.kubernetes.io/os=linux"
set +e
printf "Waiting for helm to be ready."
helm list > /dev/null
while [ $? -ne 0 ]; do
  printf "."
  sleep 2s
  helm list > /dev/null
done
set -e

printf "-- Ready!\n"


#We install isitio because it's the currently required by Knative and is support on AKS. There are other deployment options
#for KNative. The OW Router does not need istio to function, it doesn't even need the pieces of KNative that depend on
#Istio.
ISTIO_VERSION=1.2.5
curl -sL "https://github.com/istio/istio/releases/download/$ISTIO_VERSION/istio-$ISTIO_VERSION-linux.tar.gz" | tar xz
printf "Installing istio-init\n"
helm install istio-${ISTIO_VERSION}/install/kubernetes/helm/istio-init --wait --name istio-init --namespace istio-system

GRAFANA_USERNAME=$(echo -n "grafana" | base64)
GRAFANA_PASSPHRASE=$(echo -n "NQ&VZ@C882eY722YRct3T@^7mc6w33W@" | base64)

cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: Secret
metadata:
  name: grafana
  namespace: istio-system
  labels:
    app: grafana
type: Opaque
data:
  username: $GRAFANA_USERNAME
  passphrase: $GRAFANA_PASSPHRASE
EOF

KIALI_USERNAME=$(echo -n "kiali" | base64)
KIALI_PASSPHRASE=$(echo -n "DXRgYksjF3N2B65GDGJ4e8shF28!#^VG" | base64)

cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: Secret
metadata:
  name: kiali
  namespace: istio-system
  labels:
    app: kiali
type: Opaque
data:
  username: $KIALI_USERNAME
  passphrase: $KIALI_PASSPHRASE
EOF

printf "Installing istio\n"
helm install istio-${ISTIO_VERSION}/install/kubernetes/helm/istio --wait --name istio --namespace istio-system \
  --set global.controlPlaneSecurityEnabled=true \
  --set mixer.adapters.useAdapterCRDs=false \
  --set grafana.enabled=true --set grafana.security.enabled=true \
  --set tracing.enabled=true \
  --set kiali.enabled=true

kubectl get svc --namespace istio-system --output wide

rm -r ./istio-${ISTIO_VERSION}
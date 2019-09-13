#!/bin/bash
set -euxo pipefail

kubectl apply --selector knative.dev/crd-install=true \
  --filename https://github.com/knative/serving/releases/download/v0.8.0/serving.yaml \
  --filename https://github.com/knative/eventing/releases/download/v0.8.0/release.yaml \
  --filename https://github.com/knative/serving/releases/download/v0.8.0/monitoring.yaml

kubectl apply --filename https://github.com/knative/serving/releases/download/v0.8.0/serving.yaml \
  --filename https://github.com/knative/eventing/releases/download/v0.8.0/release.yaml \
  --filename https://github.com/knative/serving/releases/download/v0.8.0/monitoring.yaml

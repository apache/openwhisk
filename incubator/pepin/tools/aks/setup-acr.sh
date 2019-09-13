#!/bin/bash
#set -x
# Modify for your environment.
# ACR_NAME: The name of your Azure Container Registry
# SERVICE_PRINCIPAL_NAME: Must be unique within your AD tenant
# Setting up the service principals is pretty brittle, if you lost the secret or just need to start again delete them:
#  az ad sp delete --id <id>
ACR_NAME=$1
if [ -z $ACR_NAME ]; then
  printf "You must pass the ACR name as a parameter.\n"
  return 1
fi

az aks get-credentials --resource-group ${RESOURCE_GROUP} --name ${CLUSTER_NAME} --admin
az acr create --resource-group ${RESOURCE_GROUP} --name ${ACR_NAME} --sku Basic

DEPLOY_SERVICE_PRINCIPAL_NAME=acr-deploy-principal
K8S_SERVICE_PRINCIPAL_NAME=acr-pull-only-principal

kubectl get secret acr-auth 2&> /dev/null
K8S_SECRET=$?
currentId=$(az ad sp show --id http://${DEPLOY_SERVICE_PRINCIPAL_NAME} --query appId --output tsv)
DEPLOY_PRINCIPAL=$?
if [ ${DEPLOY_PRINCIPAL} -eq 0 ]; then
  printf "Deploy ID: %s\n" ${currentId}
fi

currentId=$(az ad sp show --id http://${K8S_SERVICE_PRINCIPAL_NAME} --query appId --output tsv)
PULL_PRINCIPAL=$?
if [ ${PULL_PRINCIPAL} -eq 0 ]; then
  printf "Pull-only ID: %s\n" ${currentId}
fi


set -euo pipefail

ACR_REGISTRY_ID=$(az acr show --name $ACR_NAME --query id --output tsv)
# Create the service principal with rights scoped to the registry.
# Default permissions are for docker pull access. Modify the '--role'
# argument value as desired:
# acrpull:     pull only
# acrpush:     push and pull
# owner:       push, pull, and assign roles
if [ ${DEPLOY_PRINCIPAL} -ne 0 ]; then
  printf "Creating deployment principal.\n"
  SP_PASSWD=$(az ad sp create-for-rbac --name http://$DEPLOY_SERVICE_PRINCIPAL_NAME --scopes $ACR_REGISTRY_ID --role acrpush --query password --output tsv)
  SP_APP_ID=$(az ad sp show --id http://$DEPLOY_SERVICE_PRINCIPAL_NAME --query appId --output tsv)
  # Output the service principal's credentials; use these in your services and
  # applications to authenticate to the container registry.
  printf "Deployment user ID: %s\n" ${SP_APP_ID}
  printf "Deployment user Password: %s\n" ${SP_PASSWD}
else
  printf "Skipping deploy principal because service principal already exists: %s\n" $DEPLOY_SERVICE_PRINCIPAL_NAME
fi

if [ ${PULL_PRINCIPAL} -ne 0 ]; then
  printf "Creating K8S principal.\n"
  SP_PASSWD=$(az ad sp create-for-rbac --name http://$K8S_SERVICE_PRINCIPAL_NAME --scopes $ACR_REGISTRY_ID --role acrpull --query password --output tsv)
  SP_APP_ID=$(az ad sp show --id http://$K8S_SERVICE_PRINCIPAL_NAME --query appId --output tsv)
  printf "export K8S_USER=%s\n" ${SP_APP_ID}
  printf "export K8S_PASSWORD=%s\n" ${SP_PASSWD}

  REGISTRY_SERVER=$(az acr show --resource-group=${RESOURCE_GROUP} --name=${ACR_NAME} --query 'loginServer' --output tsv)

  kubectl create secret docker-registry acr-auth --docker-server ${REGISTRY_SERVER} --docker-username $SP_APP_ID --docker-password $SP_PASSWD --docker-email foo@bar.com
else
  printf "Skipping k8s principal because service principal already exists: %s\n" $K8S_SERVICE_PRINCIPAL_NAME
fi


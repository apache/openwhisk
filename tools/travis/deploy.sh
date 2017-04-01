#!/bin/bash
set -eu

dockerhub_image_prefix="openwhisk"
docker login -u "${DOCKER_USER}" -p "${DOCKER_PASS}"

#push couchdb snapshot with views
container="couchdb-snapshot"
#tag image with latest
time docker tag "${dockerhub_image_prefix}/${container}" \
"${dockerhub_image_prefix}/${container}"
#tag image for commit build
time docker tag "${dockerhub_image_prefix}/${container}" \
"${dockerhub_image_prefix}/${container}:${TRAVIS_BRANCH}-${TRAVIS_COMMIT::7}"
#push couchdb-snapshot to dockerhub
time docker push "${dockerhub_image_prefix}/${container}"

PUSH_CMD="time ./gradlew distDocker \
-PdockerImagePrefix=${dockerhub_image_prefix} \
-PdockerRegistry=docker.io \
-x :common:scala:distDocker \
-x tests:dat:blackbox:badproxy:distDocker \
-x tests:dat:blackbox:badaction:distDocker \
-x sdk:docker:distDocker \
-x tools:cli:distDocker \
-x core:nodejsActionBase:distDocker"

#push latest
${PUSH_CMD} -PdockerImageTag=latest
#push travis commit
${PUSH_CMD} -PdockerImageTag=${TRAVIS_BRANCH}-${TRAVIS_COMMIT::7}



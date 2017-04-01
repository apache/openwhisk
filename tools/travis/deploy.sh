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
#push to dockerhub
time docker push "${dockerhub_image_prefix}/${container}"


#push latest
time ./gradlew distDocker \
-PdockerImagePrefix=${dockerhub_image_prefix} \
-PdockerRegistry=docker.io \
-x :common:scala:distDocker \
-x tests:dat:blackbox:badproxy:distDocker \
-x tests:dat:blackbox:badaction:distDocker \
-x sdk:docker:distDocker \
-x tools:cli:distDocker \
-x core:nodejsActionBase:distDocker

#push travis commit
time ./gradlew distDocker \
-PdockerImagePrefix=${dockerhub_image_prefix} \
-PdockerRegistry=docker.io \
-PdockerImageTag=${TRAVIS_BRANCH}-${TRAVIS_COMMIT::7} \
-x :common:scala:distDocker -x tests:dat:blackbox:badproxy:distDocker \
-x tests:dat:blackbox:badaction:distDocker \
-x sdk:docker:distDocker \
-x tools:cli:distDocker \
-x core:nodejsActionBase:distDocker

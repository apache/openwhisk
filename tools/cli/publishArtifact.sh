#!/bin/bash

#
# Publish an artifact to nginx
#
# publishArtifact ARTIFACT DEPLOY_TARGET WHISK_HOME
#

ARTIFACT=$1
: ${ARTIFACT:?"ARTIFACT must be set and non-empty"}

DEPLOY_TARGET=$2
: ${DEPLOY_TARGET:?"DEPLOY_TARGET must be set and non-empty"}

WHISK_HOME=$3
: ${WHISK_HOME:?"WHISK_HOME must be set and non-empty"}

echo "PublishArtifact.sh called."
echo "ARTIFACT:        $ARTIFACT"
echo "DEPLOY_TARGET:   $DEPLOY_TARGET"
echo "WHISK_HOME:      $WHISK_HOME"

cd "$WHISK_HOME"

NGINX_DIR=`fgrep nginx.conf.dir= whisk.properties | cut -d'=' -f2`

# If the deploy target contains local, we need to copy the artifact to the correct local location.
if echo $DEPLOY_TARGET | grep -iq "local"
then
    # Copy the artifact to the nginx directory for publish. Currently the script only supports
    # the case that the artifact is published locally in the nginx container.
    mkdir -p "$NGINX_DIR"
    cp "$ARTIFACT" "$NGINX_DIR"
else
    # If you would like to publish the artifact at a remote location, please make sure it is
    # copied to the correct location.
    echo "Please make sure the artifact is copied to the correct location, since it is published remotely."
fi
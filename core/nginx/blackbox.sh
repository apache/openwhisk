#!/bin/bash

set -ex

SCRIPTDIR=$(cd $(dirname "$0") && pwd)
WHISK_DIR="$SCRIPTDIR/../.."

TMP_DIR=$(mktemp -d)

pushd $TMP_DIR
  # create dockerSkeleton dir
  mkdir dockerSkeleton

  # copy blackbox SDK files into the dockerskeleton directory
  cp ${WHISK_DIR}/sdk/docker/buildAndPush.sh dockerSkeleton
  cp ${WHISK_DIR}/sdk/docker/Dockerfile dockerSkeleton
  cp ${WHISK_DIR}/sdk/docker/example.c dockerSkeleton
  cp ${WHISK_DIR}/sdk/docker/README.md dockerSkeleton

  # rename base image in Dockerfile
  if [ "$(uname)" == "Darwin" ]; then
    sed -i "" "s|^FROM dockerskeleton.*$|FROM openwhisk/dockerskeleton|g" dockerSkeleton/Dockerfile
  else
    sed -i "s|^FROM dockerskeleton.*$|FROM openwhisk/dockerskeleton|g" dockerSkeleton/Dockerfile
  fi

  # fix file permissions
  chmod 0755 dockerSkeleton/buildAndPush.sh

  # build blackbox container artifact
  tar -czf ${SCRIPTDIR}/build/blackbox-0.1.0.tar.gz dockerSkeleton
popd

# remove tmp dir
rm -rf $TMP_DIR

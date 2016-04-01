#!/usr/bin/env bash

set -e

# Convenience script to get started with a local CouchDB.  This builds and
# instantiates an image with CouchDB running inside, then calls the Whisk
# script to setup the admin user. It also writes the corresponding
# configuration to couchdb-local.env, so make sure you're OK with it being
# overwritten.

SCRIPTDIR=$(cd $(dirname "$0") && pwd)
ROOTDIR="$SCRIPTDIR/../../../"

IMAGE_NAME="whisk/couchdb"

USER=${1:?"Please provide CouchDB admin username."}
PASS=${2:?"Please provide CouchDB admin password."}

echo "Building image..."
$ROOTDIR/tools/docker/dockerWithRetry.sh 60 build -t "$IMAGE_NAME" "$ROOTDIR/services/couchdb"

RUNNING=$(docker ps | grep "$IMAGE_NAME" | awk '{print $1}')

if [ -n "$RUNNING" ]; then
	echo "Stopping previous instance..."
	docker stop $RUNNING
fi

echo "Starting new instance..."
CID=$(docker run -i -d -p 5984:5984 "$IMAGE_NAME")

echo "CouchDB running with container id:"
echo "  $CID"

IP=$(docker inspect --format '{{ .NetworkSettings.IPAddress }}' ${CID})

echo "at address:"
echo "  $IP"

echo "Waiting for CouchDB to start up..."
sleep 2

echo "Creating admin user..."
$ROOTDIR/tools/db/couchdb/createAdmin.sh -s http -h ${IP} -p 5984 -u ${USER} -P ${PASS}

echo "Generating couchdb-local.env..."

FP="$ROOTDIR/couchdb-local.env"

echo "#!/bin/bash" > $FP
echo "OPEN_WHISK_DB_PROTOCOL=http" >> $FP
echo "OPEN_WHISK_DB_HOST=$IP" >> $FP
echo "OPEN_WHISK_DB_PORT=5984" >> $FP
echo "OPEN_WHISK_DB_USERNAME=${USER}" >> $FP
echo "OPEN_WHISK_DB_PASSWORD=${PASS}" >> $FP

echo "Done."

#!/usr/bin/env bash

# Convenience script to get started with CouchDB.  This builds and instantiates
# an image with CouchDB+SSL running inside, then calls the Whisk script to
# setup the admin user.  Make sure the user/password match your
# couchdb-local.env.

USER=${1:?"Please provide CouchDB admin username."}
PASS=${2:?"Please provide CouchDB admin password."}

echo "Building image..."
docker build -t couchbox ./services/couchdb

RUNNING=$(docker ps | grep couchbox | awk '{print $1}')

if [ -n "$RUNNING" ]; then
	echo "Stopping previous instance..."
	docker stop $RUNNING
fi

echo "Starting new instance..."
CID=$(docker run -i -d -p 6984:6984 couchbox)

echo "CouchDB running with container id:"
echo "  $CID"

IP=$(docker inspect --format '{{ .NetworkSettings.IPAddress }}' ${CID})

echo "at address:"
echo "  $IP"

echo "Waiting for CouchDB to start up..."
sleep 2

echo "Creating admin user..."
./tools/db/couchdb/createAdmin.sh -h ${IP} -p 6984 -u ${USER} -P ${PASS}

echo "Done."

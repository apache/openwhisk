#!/usr/bin/env bash

# Useful for local testing.
# USE WITH CAUTION !!

# This script is useful for testing the action proxy (or its derivatives)
# in combination with [init,run].py. Use it to rebuild the container image
# and start the proxy: delete-build-run.sh whisk/dockerskeleton.

# Removes all previously built instances.
remove=$(docker ps -a -q)
if [[ !  -z  $remove  ]]; then
    docker rm $remove
fi

image=${1:-openwhisk/dockerskeleton}
docker build -t $image .

echo ""
echo "  ---- RUNNING ---- "
echo ""

docker run -i -t -p 8080:8080 $image

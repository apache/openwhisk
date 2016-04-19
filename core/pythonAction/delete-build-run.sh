#!/usr/bin/env bash

# Useful for local testing.
# USE WITH CAUTION !!

# Removes all previously built instances.
docker rm $(docker ps -a -q)

docker build -t pythonbox .

echo ""
echo "  ---- RUNNING ---- "
echo ""

docker run -i -t -p 8100:8080 pythonbox

# Utility script to stop and clean the running Docker containers
#!/bin/bash

echo "Cleaning Docker containers ..."
docker ps -aq | xargs docker stop | xargs docker rm
echo "Cleaning dangling Docker volumes ..."
docker volume ls -qf dangling=true | xargs docker volume rm
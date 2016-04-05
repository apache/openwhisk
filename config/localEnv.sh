#!/bin/bash
#

# Where am I? Get config.
SOURCE="${BASH_SOURCE[0]}"
DIR="$( cd -P "$( dirname "$SOURCE" )" && pwd )"

DOCKER_PORT=4243

PYTHON=python

WHISK_LOGS_DIR=/tmp/wsklogs
NGINX_CONF_DIR=/tmp/nginx
if [ "$(uname)" == "Darwin" ]; then
    WHISK_LOGS_DIR=/Users/Shared/wsklogs
    NGINX_CONF_DIR=/Users/Shared/nginx
fi

#
# Setting up LOCALHOST this way rather than "localhost:localdomain" ensures
# that accessing the Docker endpoint from inside a container works
# This is the core aspect of localEnv.
#
LOCALHOST=`ifconfig | grep -Eo 'inet (addr:)?([0-9]*\.){3}[0-9]*' | grep -Eo '([0-9]*\.){3}[0-9]*' | grep -v '127.0.0.1' | head -1`
if [ "$(uname)" == "Darwin" ]; then
    if [ ! -z $DOCKER_HOST ]; then
        LOCALHOST=`echo $DOCKER_HOST | sed -e 's/tcp:\/\///g' | sed -e 's/:[0-9]*//g'`
    else
        echo 'missing DOCKER_HOST property'
        exit 1
    fi
fi

USE_DOCKER_REGISTRY=false # skip push/pull locally
USE_CLI_DOWNLOAD=false # do not download CLI
DOCKER_REGISTRY=

WHISK_TEST_AUTH=guest

#
# SSL certificate used by router
#
WHISK_SSL_CERTIFICATE=config/keys/openwhisk-self-cert.pem
WHISK_SSL_KEY=config/keys/openwhisk-self-key.pem
WHISK_SSL_CHALLENGE=openwhisk

#
# Database variables
#
WHOAMI=`whoami | tr -dc '[:alnum:]' | tr '[:upper:]' '[:lower:]'`
CLEAN_HOSTNAME=`hostname -s | tr -dc '[:alnum:]' | tr '[:upper:]' '[:lower:]'`
DB_PREFIX=${WHOAMI}_${CLEAN_HOSTNAME}_
if [ -z "$DB_PREFIX" ]; then
	echo "DB_PREFIX is empty"
	exit 1
fi
source "$DIR/dbSetup.sh"

# Hosts
ACTIVATOR_HOST=$LOCALHOST
CONSULSERVER_HOST=$LOCALHOST
CONTROLLER_HOST=$LOCALHOST
EDGE_HOST=$LOCALHOST
KAFKA_HOST=$LOCALHOST
LOADBALANCER_HOST=$LOCALHOST
ROUTER_HOST=$LOCALHOST
ZOOKEEPER_HOST=$LOCALHOST

INVOKER_HOSTS=$LOCALHOST

CLI_API_HOST=$LOCALHOST

# Override docker/service  endpoints as local is special
MAIN_DOCKER_ENDPOINT="$LOCALHOST:4243"
CONSULSERVER_DOCKER_ENDPOINT=$MAIN_DOCKER_ENDPOINT
EDGE_DOCKER_ENDPOINT=$MAIN_DOCKER_ENDPOINT
KAFKA_DOCKER_ENDPOINT=$MAIN_DOCKER_ENDPOINT



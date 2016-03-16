#!/bin/bash
#
# Convert environment variables used for deploy/testing into a whisk property file.
#
# Usage: writePropertyFile "someConfigEnv.sh"

WHISK_CONFIG_NAME=$1
: ${WHISK_CONFIG_NAME:?"WHISK_CONFIG_NAME must be set and non-empty"}
WHISK_CONFIG_NAME=`echo $WHISK_CONFIG_NAME | sed -e 's/Env.sh//'`

# Where am I? Get config.
SOURCE="${BASH_SOURCE[0]}"
DIR="$( cd -P "$( dirname "$SOURCE" )" && pwd )"

# Get root project directory
WHISK_HOME="$(dirname "$DIR")"
rm -f "$WHISK_HOME/whisk.properties"

touch "$WHISK_HOME/whisk.properties"

echo "whisk.logs.dir="$WHISK_LOGS_DIR >> "$WHISK_HOME/whisk.properties"

echo "docker.port="$DOCKER_PORT >> "$WHISK_HOME/whisk.properties"
echo "docker.timezone.mount="$DOCKER_TIMEZONE_MOUNT >> "$WHISK_HOME/whisk.properties"
echo "docker.image.tag="$DOCKER_IMAGE_TAG >> "$WHISK_HOME/whisk.properties"

# Python
echo "python.27="$PYTHON  >> "$WHISK_HOME/whisk.properties"

# Use published CLI distribution
echo "use.cli.download="$USE_CLI_DOWNLOAD >> "$WHISK_HOME/whisk.properties"

echo "nginx.conf.dir="$NGINX_CONF_DIR >> "$WHISK_HOME/whisk.properties"

# Write configuration information to whisk.properties
echo "whisk.version.name" = $WHISK_CONFIG_NAME >> "$WHISK_HOME/whisk.properties"
echo "whisk.version.date" = `date '+%Y-%m-%dT%H:%M:%S%:z' ` >> "$WHISK_HOME/whisk.properties"
echo "whisk.version.buildno" = "$DOCKER_IMAGE_TAG" >> "$WHISK_HOME/whisk.properties"

# Authorization to use for unit tests
echo "testing.auth=$WHISK_TEST_AUTH" >> "$WHISK_HOME/whisk.properties"

# Self-signed certificate
echo "whisk.ssl.cert=$WHISK_SSL_CERTIFICATE" >> "$WHISK_HOME/whisk.properties"
echo "whisk.ssl.key=$WHISK_SSL_KEY" >> "$WHISK_HOME/whisk.properties"
echo "whisk.ssl.challenge=$WHISK_SSL_CHALLENGE" >> "$WHISK_HOME/whisk.properties"

#Hosts
echo "activator.host="$ACTIVATOR_HOST >> "$WHISK_HOME/whisk.properties"
echo "consulserver.host="$CONSULSERVER_HOST >> "$WHISK_HOME/whisk.properties"
echo "controller.host="$CONTROLLER_HOST >> "$WHISK_HOME/whisk.properties"
echo "edge.host="$EDGE_HOST >> "$WHISK_HOME/whisk.properties"
echo "kafka.host="$KAFKA_HOST >> "$WHISK_HOME/whisk.properties"
echo "loadbalancer.host="$LOADBALANCER_HOST >> "$WHISK_HOME/whisk.properties"
echo "router.host="$ROUTER_HOST >> "$WHISK_HOME/whisk.properties"
echo "cli.api.host="$CLI_API_HOST >> "$WHISK_HOME/whisk.properties"
echo "zookeeper.host="$ZOOKEEPER_HOST >> "$WHISK_HOME/whisk.properties"

echo "invoker.hosts="$INVOKER_HOSTS >> "$WHISK_HOME/whisk.properties"
if [ "$INVOKER_CONTAINER_NETWORK" != "" ]; then
    echo "invoker.container.network="$INVOKER_CONTAINER_NETWORK >> "$WHISK_HOME/whisk.properties"
else
    echo "invoker.container.network=bridge" >> "$WHISK_HOME/whisk.properties"
fi


# Docker endpoints
echo "consulserver.docker.endpoint="$CONSULSERVER_DOCKER_ENDPOINT >> "$WHISK_HOME/whisk.properties"
echo "edge.docker.endpoint="$EDGE_DOCKER_ENDPOINT >> "$WHISK_HOME/whisk.properties"
echo "kafka.docker.endpoint="$KAFKA_DOCKER_ENDPOINT >> "$WHISK_HOME/whisk.properties"
echo "main.docker.endpoint="$MAIN_DOCKER_ENDPOINT >> "$WHISK_HOME/whisk.properties"

# Remote docker registry
if [ "$DOCKER_REGISTRY" == "" ]; then
    echo "docker.registry="$DOCKER_REGISTRY >> "$WHISK_HOME/whisk.properties"
else
    echo "docker.registry="$DOCKER_REGISTRY"/" >> "$WHISK_HOME/whisk.properties"
fi
echo "use.docker.registry="$USE_DOCKER_REGISTRY >> "$WHISK_HOME/whisk.properties"


# Docker TLS
echo ""
if [ "$DOCKER_TLS" == "true" ]; then
    echo "docker.tls="$DOCKER_TLS >> "$WHISK_HOME/whisk.properties"
    echo "docker.tls.verify="$DOCKER_TLS_VERIFY >> "$WHISK_HOME/whisk.properties"
    echo "docker.tls.cacert="$DOCKER_TLS_CACERT >> "$WHISK_HOME/whisk.properties"
    echo "docker.tls.cert="$DOCKER_TLS_CERT >> "$WHISK_HOME/whisk.properties"
    echo "docker.tls.key="$DOCKER_TLS_KEY >> "$WHISK_HOME/whisk.properties"
    echo "docker.tls.cmd=--tlsverify=$DOCKER_TLS_VERIFY --tlscacert=$DOCKER_TLS_CACERT --tlscert=$DOCKER_TLS_CERT --tlskey=$DOCKER_TLS_KEY" >> "$WHISK_HOME/whisk.properties"
else
    echo "docker.tls.cmd=" >> "$WHISK_HOME/whisk.properties"
fi

# Docker /etc/hosts mappings
echo ""
if [ "$DOCKER_ADD_HOSTS" != "" ]; then
    DOCKER_ADD_HOST_CMD=""
    for host in $DOCKER_ADD_HOSTS; do
        DOCKER_ADD_HOST_CMD="$DOCKER_ADD_HOST_CMD --add-host $host"
    done
    echo "docker.addHost.cmd=$DOCKER_ADD_HOST_CMD" >> "$WHISK_HOME/whisk.properties"
else
    echo "docker.addHost.cmd=" >> "$WHISK_HOME/whisk.properties"
fi

# Docker DNS entries
echo ""
if [ "$DOCKER_DNS" != "" ]; then
    DOCKER_DNS_CMD=""
    for dns in $DOCKER_DNS; do
        DOCKER_DNS_CMD="$DOCKER_DNS_CMD --dns $dns"
    done
    echo "docker.dns.cmd=$DOCKER_DNS_CMD" >> "$WHISK_HOME/whisk.properties"
else
    echo "docker.dns.cmd=" >> "$WHISK_HOME/whisk.properties"
fi


# Docker run parameter to restart containers after reboot
if [ "$DOCKER_RESTART_OPTS" != "" ]; then
    echo "docker.restart.opts=--restart $DOCKER_RESTART_OPTS" >> "$WHISK_HOME/whisk.properties"
else
    echo "docker.restart.opts=" >> "$WHISK_HOME/whisk.properties"
fi

# Port mapping - carefully allocate a host port that will not conflict
# with another service since a service may end up running on the same host
echo "edge.host.uiport=443" >> "$WHISK_HOME/whisk.properties"
echo "edge.host.apiport=443" >> "$WHISK_HOME/whisk.properties"

echo "zookeeper.host.port=2181" >> "$WHISK_HOME/whisk.properties"

echo "kafka.host.port=9092" >> "$WHISK_HOME/whisk.properties"
echo "kafkaras.host.port=9093" >> "$WHISK_HOME/whisk.properties"

echo "controller.host.port=10001" >> "$WHISK_HOME/whisk.properties"
echo "loadbalancer.host.port=10003" >> "$WHISK_HOME/whisk.properties"

echo "activator.host.port=12000" >> "$WHISK_HOME/whisk.properties"

# Port mapping for container - this is the port the service binds to inside
# the container; unless there are more than one service inside a container,
# use a standard port like 8080. Of course if the service requires a particular
# port use that port.
echo "activator.docker.port=8080" >> "$WHISK_HOME/whisk.properties"
echo "controller.docker.port=8080" >> "$WHISK_HOME/whisk.properties"
echo "loadbalancer.docker.port=8080" >> "$WHISK_HOME/whisk.properties"
echo "kafka.docker.port=9092" >> "$WHISK_HOME/whisk.properties"
echo "kafkaras.docker.port=8080" >> "$WHISK_HOME/whisk.properties"
echo "invoker.docker.port=8080" >> "$WHISK_HOME/whisk.properties"
echo "zookeeper.docker.port=2181" >> "$WHISK_HOME/whisk.properties"
#Ports for consul
#8301 8301/udp 8302 8302/udp 8400 8500 8600 8600/udp
echo "consul.docker.port1=8301" >> "$WHISK_HOME/whisk.properties"
echo "consul.docker.port2=8302" >> "$WHISK_HOME/whisk.properties"
echo "consul.docker.port3=8400" >> "$WHISK_HOME/whisk.properties"
echo "consul.docker.port4=8500" >> "$WHISK_HOME/whisk.properties"
echo "consul.docker.port5=53" >> "$WHISK_HOME/whisk.properties"

echo "consul.host.port4=8500" >> "$WHISK_HOME/whisk.properties"
echo "consul.host.port5=8600" >> "$WHISK_HOME/whisk.properties"


echo "invoker.hosts.baseport=12001" >> "$WHISK_HOME/whisk.properties"

# DB
echo "db.provider="$OPEN_WHISK_DB_PROVIDER >> "$WHISK_HOME/whisk.properties"
echo "db.prefix="$DB_PREFIX >> "$WHISK_HOME/whisk.properties"
echo "db.whisk.actions="$DB_WHISK_ACTIONS >> "$WHISK_HOME/whisk.properties"
echo "db.whisk.auths="$DB_WHISK_AUTHS >> "$WHISK_HOME/whisk.properties"

if [ "$OPEN_WHISK_DB_PROVIDER" == "Cloudant" ]; then
    # Cloudant
    echo "db.host=$CLOUDANT_USERNAME.cloudant.com" >> "$WHISK_HOME/whisk.properties"
    echo "db.port=443" >> "$WHISK_HOME/whisk.properties"
    echo "db.username="$CLOUDANT_USERNAME >> "$WHISK_HOME/whisk.properties"
    echo "db.password="$CLOUDANT_PASSWORD >> "$WHISK_HOME/whisk.properties"
elif [ "$OPEN_WHISK_DB_PROVIDER" == "CouchDB" ]; then
    # CouchDB
    echo "db.host="$COUCHDB_HOST >> "$WHISK_HOME/whisk.properties"
    echo "db.port="$COUCHDB_PORT >> "$WHISK_HOME/whisk.properties"
    echo "db.username="$COUCHDB_USERNAME >> "$WHISK_HOME/whisk.properties"
    echo "db.password="$COUCHDB_PASSWORD >> "$WHISK_HOME/whisk.properties"
else
    echo "Error: unknown DB provider: '$OPEN_WHISK_DB_PROVIDER'"
    exit 1
fi

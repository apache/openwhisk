#!/bin/bash

# start a registrator container on each of the docker endpoints in the system

: ${CONSUL_SERVER:?"CONSUL_SERVER must be set and non-empty"}
: ${CONSUL_PORT:?"CONSUL_PORT must be set and non-empty"}
: ${WHISK_LOGS_DIR:?"WHISK_LOGS_DIR must be set and non-empty"}
: ${WHISK_HOME}:?"WHISK_HOME must be set and non-empty"}
: ${WHISKPROPS}:?"WHISKPROPS must be set and non-empty"}

# get all docker hosts
ALL_HOSTS=`"$WHISK_HOME/tools/docker/listAllDockerHosts.sh" "$WHISKPROPS"`
DEFAULT_DOCKER_PORT=4243

RESYNC_SEC=2 # interval for registrator service resync

host_cnt=0


# Test an IP address for validity:
# Usage:
#      valid_ip IP_ADDRESS
#      if [[ $? -eq 0 ]]; then echo good; else echo bad; fi
#   OR
#      if valid_ip IP_ADDRESS; then echo good; else echo bad; fi
#
function valid_ip()
{
    local  ip=$1
    local  stat=1

    if [[ $ip =~ ^[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}$ ]]; then
        OIFS=$IFS
        IFS='.'
        ip=($ip)
        IFS=$OIFS
        [[ ${ip[0]} -le 255 && ${ip[1]} -le 255 \
            && ${ip[2]} -le 255 && ${ip[3]} -le 255 ]]
        stat=$?
    fi
    return $stat
}

# mount docker socket for registrator to access it and mount whisk log dir
VOLUMES="-v /var/run/docker.sock:/tmp/docker.sock -v $WHISK_LOGS_DIR/registrator:/logs"
echo "ALL_HOSTS $ALL_HOSTS"
for host in $ALL_HOSTS
do
    # get ip of the host to enforce service to be advertised with this ip 
    if valid_ip $host; then host_ip=$host; else host_ip=`dig +short $host | tail -1`; fi
    DOCKER_ENDPOINT=$host:${DOCKER_PORT:-$DEFAULT_DOCKER_PORT}     

    cmd="docker --host tcp://${DOCKER_ENDPOINT} $DOCKER_TLS_CMD run -d $VOLUMES \
                   $DOCKER_RESTART_OPTS \
                   $DOCKER_TIMEZONE_MOUNT \
                   --log-driver=syslog \
                   --name registrator \
                  gliderlabs/registrator -ip $host_ip -resync $RESYNC_SEC consul://$CONSUL_SERVER:$CONSUL_PORT" 
    echo Running: $cmd
    $cmd &
    
    pid=$!
    pids[$host_cnt]=$pid
    hosts[$host_cnt]=$host
    host_cnt=$((host_cnt + 1))
done

ret=0
for ((h=0; h < host_cnt; h = h + 1));
do
    host=${hosts[$h]}
    pid=${pids[$h]}
    wait $pid
    # echo $pid $host
    rc=$?; if [[ $rc != 0 ]]; then echo "FAILED: Deploying registrator on $host failed with exit code $rc"; fi
    ret=$((ret + rc))
done
exit $ret

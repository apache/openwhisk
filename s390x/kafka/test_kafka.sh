#!/bin/bash

REPOSITORY=${REPOSITORY:-jpspring/s390x-openwhisk}
KAFKA_LABEL=${LABEL:-kafka}
ZOOKEEPER_LABEL=${LABEL:-zookeeper}

KAFKA_DIR=$(dirname $(realpath -s $0))
finish() {
  for id in "$kafka_id" "$zookeeper_id"; do
    if [ -n "$id" ]; then
      echo 'Killing then removing container'
      echo -n 'Kill...'; docker kill $id
      echo -n 'Remove...'; docker rm $id
    fi
  done
}
trap finish EXIT

#id=$(docker run -v "$CONSULDIR/config:/consul/config" -v "$CONSULDIR/logs:/logs" -p 8500:8500 -d "$REPOSITORY:$LABEL")
zookeeper_id=$(docker run -d "$REPOSITORY:$ZOOKEEPER_LABEL")
if [ -z "$zookeeper_id" ]; then echo "Could not create zookeeper image"; exit 1; fi
zookeeper_ip=$(docker inspect --format '{{ .NetworkSettings.IPAddress }}' $zookeeper_id)
echo Successfully started Zookeeper container $zookeeper_id at IP $zookeeper_ip

kafka_id=$(docker run -e ZOOKEEPER_IP=$zookeeper_ip -e ZOOKEEPER_PORT=2181 \
               -d "$REPOSITORY:$KAFKA_LABEL")
if [ -z "$kafka_id" ]; then echo "Could not create kafka image"; exit 1; fi
kafka_ip=$(docker inspect --format '{{ .NetworkSettings.IPAddress }}' $kafka_id)
echo Successfully started Kafka container $kafka_id at IP $kafka_ip

#url="http://localhost:8500/v1/kv/consulIsAlive"
#url="http://$ip:8500/v1/kv/consulIsAlive"

#echo "Testing container $id at $url in 3 seconds..."

#sleep 3

# for url in \
#   "http://$ip:8500/v1/kv/consulIsAlive" \
#   "http://localhost:8500/v1/kv/consulIsAlive" \
#   ;\
# do
#   echo "Testing at URL $url"
#   result=$(curl --connect-timeout 4 --max-time 5 -XPUT "$url")
#   if [ "$result" = "true" ]; then echo '.'; else exit 1; fi
#
#   result=$(curl --connect-timeout 4 --max-time 5 -XDELETE "$url")
#   if [ "$result" = "true" ]; then echo '.';else exit 1; fi
# done

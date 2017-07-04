#!/bin/bash

REPOSITORY=${REPOSITORY:-jpspring/s390x-openwhisk}
LABEL=${LABEL:-consul}

CONSULDIR=$(dirname $(realpath -s $0))
finish() {
  for id in "$consul_id" "$alpine_id"; do
    if [ -n "$id" ]; then
      echo 'Killing then removing container'
      echo -n 'Kill...'; docker kill $id
      echo -n 'Remove...'; docker rm $id
    fi
  done
}
trap finish EXIT

#id=$(docker run -d -P "$REPOSITORY:$LABEL")
consul_id=$(docker run -v "$CONSULDIR/config:/consul/config" -v "$CONSULDIR/logs:/logs" -p 8500:8500 -d "$REPOSITORY:$LABEL")
if [ -z "$consul_id" ]; then echo "Could not create image"; exit 1; fi

ip=$(docker inspect --format '{{ .NetworkSettings.IPAddress }}' $consul_id)

echo "Testing container $id at $url in 3 seconds..."; sleep 3

for url in \
  "http://$ip:8500/v1/kv/consulIsAlive" \
  "http://localhost:8500/v1/kv/consulIsAlive" \
  "http://172.17.0.1:8500/v1/kv/consulIsAlive" \
  "http://gcobonz.dyndns.org:8500/v1/kv/consulIsAlive" \
  ;\
do
  echo "Testing at URL $url"
  result=$(curl --connect-timeout 4 --max-time 5 -XPUT "$url")
  if [ "$result" = "true" ]; then echo '.'; else exit 1; fi

  result=$(curl --connect-timeout 4 --max-time 5 -XDELETE "$url")
  if [ "$result" = "true" ]; then echo '.';else exit 1; fi
done

#  Now we get fancy -- test from from a running machine
alpine_id=$(docker run -d s390x/alpine tail -f /dev/null)
docker exec $alpine_id /bin/sh -c 'apk add --no-cache curl'
for url in \
  "http://$ip:8500/v1/kv/consulIsAlive" \
  "http://172.17.0.1:8500/v1/kv/consulIsAlive" \
  ; \
do
  echo "Testing at URL $url on an alpine box"
  result=$(docker exec $alpine_id /bin/sh -c "curl --connect-timeout 4 --max-time 5 -XPUT \"$url\"")
  if [ "$result" = "true" ]; then echo '.'; else exit 1; fi

  result=$(docker exec $alpine_id /bin/sh -c "curl --connect-timeout 4 --max-time 5 -XDELETE \"$url\"")
  if [ "$result" = "true" ]; then echo '.';else exit 1; fi
done

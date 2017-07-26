#!/bin/bash

set -mx

# start the kafka process
/start.sh &

TIMEOUT=0
echo "wait for Kafka to be up and running"
until [ $TIMEOUT -eq 25 ]; do
  echo "waiting for kafka to be available"

  nc -z 127.0.0.1 9092
  if [ $? -eq 0 ]; then
    echo "kafka is up and running"
    break
  fi

  sleep 0.2
  let TIMEOUT=TIMEOUT+1
done

if [ $TIMEOUT -eq 25 ]; then
  echo "failed to setup and reach kafka"
  exit 1
fi

echo "Creating health topic"
OUTPUT=$(unset JMX_PORT; /kafka/bin/kafka-topics.sh --create --topic health --replication-factor $REPLICATION_FACTOR --partitions $PARTITIONS --zookeeper ${ZOOKEEPER_HOST}:${ZOOKEEPER_PORT} --config retention.bytes=$KAFKA_TOPICS_HEALTH_RETENTIONBYTES --config retention.ms=$KAFKA_TOPICS_HEALTH_RETENTIONMS --config segment.bytes=$KAFKA_TOPICS_HEALTH_SEGMENTBYTES)
if ! ([[ "$OUTPUT" == *"already exists"* ]] || [[ "$OUTPUT" == *"Created topic"* ]]); then
  echo "Failed to create Health topic"
  exit 1
fi

echo "Created Health topics and Kafka is running"

# give controll back to the kafka process
fg

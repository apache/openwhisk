#!/bin/bash

set -x

if [ -z "$1" ]; then
  echo "Controller index not passed as first argument to startup script"
  exit 1
fi

CONTROLLER_INDEX=$1

echo "Create controller topics"
OUTPUT=$(/kafka/bin/kafka-topics.sh --create --topic completed$CONTROLLER_INDEX --replication-factor $REPLICATION_FACTOR --partitions $PARTITIONS --zookeeper ${ZOOKEEPER_HOST}:${ZOOKEEPER_PORT} --config retention.bytes=$KAFKA_TOPICS_COMPLETED_RETENTIONBYTES --config retention.ms=$KAFKA_TOPICS_COMPLETED_RETENTIONMS --config segment.bytes=$KAFKA_TOPICS_COMPLETED_SEGMENTBYTES)

if ! ([[ "$OUTPUT" == *"already exists"* ]] || [[ "$OUTPUT" == *"Created topic"* ]]); then
  echo "Failed to create invoker$i topic"
  exit 1
fi

# Start command is defined in deployment option, Ansible, Kubernetes, etc
exec /controller/bin/controller $CONTROLLER_INDEX

#!/bin/bash

set -x

echo "Create invoker topics"
OUTPUT=$(/kafka/bin/kafka-topics.sh --create --topic invoker$INVOKER_INDEX --replication-factor $REPLICATION_FACTOR --partitions $PARTITIONS --zookeeper ${ZOOKEEPER_HOST}:${ZOOKEEPER_PORT} --config retention.bytes=$KAFKA_TOPICS_INVOKER_RETENTIONBYTES --config retention.ms=$KAFKA_TOPICS_INVOKER_RETENTIONMS --config segment.bytes=$KAFKA_TOPICS_INVOKER_SEGMENTBYTES)

if ! ([[ "$OUTPUT" == *"already exists"* ]] || [[ "$OUTPUT" == *"Created topic"* ]]); then
  echo "Failed to create invoker$i topic"
  exit 1
fi

# Start command is defined in deployment option, Ansible, Kubernetes, etc
exec /invoker/bin/invoker $INVOKER_INDEX

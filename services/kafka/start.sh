#!/bin/bash

echo "Preparing to start kafka"

# If a ZooKeeper container is linked with the alias `zookeeper`, use it.
# TODO Service discovery otherwise
[ -n "$ZOOKEEPER_PORT_2181_TCP_ADDR" ] && ZOOKEEPER_IP=$ZOOKEEPER_PORT_2181_TCP_ADDR
[ -n "$ZOOKEEPER_PORT_2181_TCP_PORT" ] && ZOOKEEPER_PORT=$ZOOKEEPER_PORT_2181_TCP_PORT

IP=$(cat /etc/hosts | head -n1 | awk '{print $1}')
PORT=9092

cat /kafka/config/server.properties.default \
  | sed "s|{{ZOOKEEPER_IP}}|${ZOOKEEPER_IP}|g" \
  | sed "s|{{ZOOKEEPER_PORT}}|${ZOOKEEPER_PORT:-2181}|g" \
  | sed "s|{{BROKER_ID}}|${BROKER_ID:-0}|g" \
  | sed "s|{{CHROOT}}|${CHROOT:-}|g" \
  | sed "s|{{EXPOSED_HOST}}|${EXPOSED_HOST:-$IP}|g" \
  | sed "s|{{PORT}}|${PORT:-9092}|g" \
  | sed "s|{{EXPOSED_PORT}}|${EXPOSED_PORT:-9092}|g" \
   > /kafka/config/server.properties

echo "Environment"
echo "-----------"
echo "IP = $IP"
echo "PORT = $PORT"
echo "CLASSPATH = $CLASSPATH"
echo "JMX_PORT = $JMX_PORT"
echo "ZOOKEEPER_IP = $ZOOKEEPER_IP"
echo "ZOOKEEPER_PORT = $ZOOKEEPER_PORT"

echo ""
echo "Starting kafka"
echo "--------------"
/kafka/bin/kafka-server-start.sh /kafka/config/server.properties


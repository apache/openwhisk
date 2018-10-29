# OpenWhisk User Events

This service connects to `events` topic and publishes the events to to various services like Prometheus, Datadog etc via 
Kamon.

Run the container like

```bash
docker build -t openwhisk-user-metrics .
docker run  -p 9095:9095 --name user-metrics -e "KAFKA_HOSTS=172.17.0.1:9093" openwhisk-user-metrics
```

Here
* `KAFKA_HOSTS` - Is the host address of Kafka cluster. For local OpenWhisk events it defaults to _172.17.0.1:9093_

The docker container would run the service and expose the metrics in format required by Prometheus at `9095` port
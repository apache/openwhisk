# OpenWhisk User Events

This service connects to `events` topic and publishes the events to to various services like Prometheus, Datadog etc via 
Kamon. Refer to [user specific metrics][1] on how to enable them

## Build
```bash
docker build -t openwhisk/user-metrics .
```

## Run
The container will be run inside the openwhisk [docker-compose][2] environment
```bash
docker run  -p 9095:9095 --name user-metrics -e "KAFKA_HOSTS=172.17.0.1:9093"  --network openwhisk_default openwhisk/user-metrics
```

> `KAFKA_HOSTS` - Is the host address of Kafka cluster. For local OpenWhisk events it defaults to _172.17.0.1:9093_

## Deploy
New versions of the application will be built and deployed to [Artifactory][3] using [Jenkins][4]. 

Integrations
------------

#### Prometheus
The docker container would run the service and expose the metrics in format required by Prometheus at `9095` port

[1]: https://github.com/apache/incubator-openwhisk/blob/master/docs/metrics.md#user-specific-metrics
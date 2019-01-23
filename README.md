# ![OpenWhisk User Events](https://raw.githubusercontent.com/adobe-apiplatform/openwhisk-user-events/grafana-ui/images/demo_landing.png)

# OpenWhisk User Events

[![Build Status](https://travis-ci.org/adobe-apiplatform/openwhisk-user-events.svg?branch=master)](https://travis-ci.org/adobe-apiplatform/openwhisk-user-events)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0)
[![codecov](https://codecov.io/gh/adobe-apiplatform/openwhisk-user-events/branch/master/graph/badge.svg)](https://codecov.io/gh/adobe-apiplatform/openwhisk-user-events)

This service connects to `events` topic and publishes the events to various services like Prometheus, Datadog etc via Kamon. Refer to [user specific metrics][1] on how to enable them

## Build

This command pulls the docker images for local testing and development.

```bash
make all
```

## Run
>First configure and run `openwhisk docker-compose` that can be found in the [openwhisk-tools][2] project. 

Once the `openwhisk docker-compose` has been started, go ahead and execute the following command:  

```bash
make start-docker-compose
```

This will start the `user-event` service along with [prometheus][3] and [grafana][4] inside the same [docker-compose openwhisk][2] network. 

These ports must be available:

- `9095` - user-events service
- `9096` - prometheus
- `3000` - grafana

## Logs

- `docker-compose` logs - `~/tmp/openwhisk/docker-compose-events.log`

## Building a Docker image

A docker image of the `user-events` service can be build running this command: 
```bash
make docker-build
```
The latest docker image can also be found on docker hub under this name: [adobeapiplatform/openwhisk-user-events][7].

This image can be deployed in any other configuration that doesn't include `docker-compose`, as long as the environment variable `KAFKA_HOSTS` is being set to point to the existing openwhisk Kafka URL.

Integrations
------------

#### Prometheus
The docker container would run the service and expose the metrics in format required by Prometheus at `9095` port

#### Grafana
The `Openwhisk - Action Performance Metrics` grafana dashboard is available on localhost port `3000` at this address: 
[http://localhost:3000/d/Oew1lvymk/openwhisk-action-performance-metrics][5]

The latest version of the dashboard can be found on [Grafana Labs][6].

[1]: https://github.com/apache/incubator-openwhisk/blob/master/docs/metrics.md#user-specific-metrics
[2]: https://github.com/apache/incubator-openwhisk-devtools/tree/master/docker-compose
[3]: https://hub.docker.com/r/prom/prometheus/
[4]: https://hub.docker.com/r/grafana/grafana/
[5]: http://localhost:3000/d/Oew1lvymk/openwhisk-action-performance-metrics
[6]: https://grafana.com/dashboards/9564
[7]: https://cloud.docker.com/u/adobeapiplatform/repository/docker/adobeapiplatform/openwhisk-user-events
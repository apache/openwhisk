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
```bash
make start-docker-compose
```

This command will start the `user-event` service along with [prometheus][3] and [grafana][4] inside the same [docker-compose openwhisk][2] network. 


These ports must be available:

- `9095` - user-events service
- `9096` - prometheus
- `3000` - grafana

## Logs

- `docker-compose` logs - `~/tmp/openwhisk/docker-compose-events.log`

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
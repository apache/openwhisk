# OpenWhisk User Events

[![Build Status](https://travis-ci.org/adobe-apiplatform/openwhisk-user-events.svg?branch=master)](https://travis-ci.org/adobe-apiplatform/openwhisk-user-events)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0)
[![codecov](https://codecov.io/gh/adobe-apiplatform/openwhisk-user-events/branch/master/graph/badge.svg)](https://codecov.io/gh/adobe-apiplatform/openwhisk-user-events)

This service connects to `events` topic and publishes the events to various services like Prometheus, Datadog etc via Kamon. Refer to [user specific metrics][1] on how to enable them

## Build

This command pulls the docker images for local testing and development.

```bash
make docker-build
```

## Run
The container will be run inside the openwhisk [docker-compose][2] environment

```bash
make run
```

This command would starts the user-events service along with [Prometheus][4] and Grafana[5]

These ports must be available:

- `9095` - user-events service
- `9096` - prometheus
- `3000` - Grafana

## Logs

- `docker-compose` logs - `~/tmp/openwhisk/docker-compose-events.log`

Integrations
------------

#### Prometheus
The docker container would run the service and expose the metrics in format required by Prometheus at `9095` port

[1]: https://github.com/apache/incubator-openwhisk/blob/master/docs/metrics.md#user-specific-metrics
[2]: https://github.com/apache/incubator-openwhisk-devtools/tree/master/docker-compose

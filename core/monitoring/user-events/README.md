<!--
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
-->

# ![OpenWhisk User Events](https://raw.githubusercontent.com/apache/openwhisk/master/core/monitoring/user-events/images/demo_landing.png)

# OpenWhisk User Events

This service connects to `events` topic and publishes the events to various services like Prometheus, Datadog etc via Kamon. Refer to [user specific metrics][1] on how to enable them.


## Local Run
>First configure and run `openwhisk docker-compose` that can be found in the [openwhisk-tools][2] project.

- Start service inside the cluster (on the same docker-compose network: `openwhisk_default`)
- The service will be available on port `9095`
- The endpoint for exposing the metrics for Prometheus can be found on `/metrics`.

## Usage

The service needs the following env variables to be set

- `KAFKA_HOSTS` - For local env it can be set to `172.17.0.1:9093`. When using [OpenWhisk Devtools][2] based setup use `kafka`
- Namespaces can be removed from reports by listing them inside the `reference.conf` using the `whisk.user-events.ignored-namespaces` configuration.
e.g:
```
whisk {
  user-events {
    ignored-namespaces = ["canary","testing"]
  }
}
```
- To rename metrics tags, use the below configuration. Currently, this configuration only applies to the Prometheus
Metrics. For example, here `namespace` tag name will be replaced by `ow_namespace` in all metrics.

```
whisk {
  user-events {
    rename-tags {
      # rename/relabel prometheus metrics tags
      "namespace" = "ow_namespae"
     }
  }
}
```

Integrations
------------

#### Prometheus
The docker container would run the service and expose the metrics in format required by [Prometheus][3] at `9095` port

#### Grafana
The `Openwhisk - Action Performance Metrics` Grafana[4] dashboard is available on localhost port `3000` at this address:
http://localhost:3000/d/Oew1lvymk/openwhisk-action-performance-metrics

The latest version of the dashboard can be found in the "compose/dashboard/openwhisk_events.json"

[1]: https://github.com/apache/openwhisk/blob/master/docs/metrics.md#user-specific-metrics
[2]: https://github.com/apache/openwhisk-devtools/tree/master/docker-compose
[3]: https://hub.docker.com/r/prom/prometheus/
[4]: https://hub.docker.com/r/grafana/grafana/

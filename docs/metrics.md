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
# OpenWhisk Metric Support

OpenWhisk distinguishes between system and user metrics (events).

System metrics typically contain information about system performance and provide a possibility to send them to Kamon or write them to log files in logmarker format. These metrics are typically used by OpenWhisk providers/operators.

User metrics encompass information about action performance which is sent to Kafka in a form of events. These metrics are to be consumed by OpenWhisk users, however they could be also used for billing or audit purposes. It is to be noted that at the moment the events are not directly exposed to the users and require an additional Kafka Consumer based micro-service for data processing.

## System specific metrics
### Configuration

Both capabilities can be enabled or disabled separately during deployment via Ansible configuration in the 'group_vars/all' file of an environment.

There are four configurations options available:

- **metrics_log** [true / false  (default: true)]

  Enable/disable whether the metric information is written out to the log files in logmarker format.

  *Beware: Even if set to false all messages using the log markers are still written out to the log*

- **metrics_kamon** [true / false (default: false)]

  Enable/disable whether metric information is sent to the configured StatsD server.

- **metrics_kamon_tags: false** [true / false  (default: false)]

  Enable/disable whether to use the Kamon tags when sending metrics.

  *Notice: Tags are supported in only some Kamon backends. (OpenTSDB, Datadog, InfluxDB)*

- **metrics_kamon_statsd_host** [hostname or ip address]

  Hostname or ip address of the StatsD server

- **metrics_kamon_statsd_port** [port number (default:8125)]

  Port number of the StatsD server

Example configuration:

```
metrics_kamon: true
metrics_kamon_tags: false
metrics_kamon_statsd_host: '192.168.99.100'
metrics_kamon_statsd_port: '8125'
metrics_log: true
```

### Testing the StatsD metric support

The Kamon project provides an integrated docker image containing StatsD and a connected Grafana dashboard via [this Github project](https://github.com/kamon-io/docker-grafana-graphite). This image is helpful for testing the metrics sent via StatsD.

Please follow these [instructions](https://github.com/kamon-io/docker-grafana-graphite/blob/master/README.md) to start the docker image in your local docker environment.

The docker image exposes StatsD via the (standard) port 8125 and a Grafana dashboard via port 8080 on your docker host.

The address of your docker host has to be configured in the `metrics_kamon_statsd_host` configuration property.

## User specific metrics
### Configuration
User metrics are enabled by default and could be explicitly disabled by setting the following property in one of the Ansible configuration files:
```
user_events: false
```

### Supported events
Activation is an event that occurs after after each activation. It includes the following execution metadata:
```
waitTime - internal system hold time
initTime - time it took to initialize an action, e.g. docker init
statusCode - status code of the invocation: 0 - success, 1 - application error, 2 - action developer error, 3 - internal OpenWhisk error
duration - actual time the action code was running
kind - action flavor, e.g. Node.js
conductor - true for conductor backed actions
memory - maximum memory allowed for action container
causedBy - true for sequence actions
```
Metric is any user specific event produced by the system and it at this moment includes the following information:
```
ConcurrentRateLimit - a user has exceeded its limit for concurrent invocations.
TimedRateLimit - the user has reached its per minute limit for the number of invocations.
ConcurrentInvocations - the number of in flight invocations per user.
```

Example events that could be consumed from Kafka.
Activation:
```
{"body":{"statusCode":0,"duration":3,"name":"whisk.system/invokerHealthTestAction0","waitTime":583915671,"conductor":false,"kind":"nodejs:6","initTime":0,"memory": 256, "causedBy": false},"eventType":"Activation","source":"invoker0","subject":"whisk.system","timestamp":1524476122676,"userId":"d0888ad5-5a92-435e-888a-d55a92935e54","namespace":"whisk.system"}
```
Metric:
```
{"body":{"metricName":"ConcurrentInvocations","metricValue":1},"eventType":"Metric","source":"controller0","subject":"guest","timestamp":1524476104419,"userId":"23bc46b1-71f6-4ed5-8c54-816aa4f8c502","namespace":"guest"}
```

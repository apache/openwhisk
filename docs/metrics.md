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

### Metric Names

All metric names have to be prefixed by a prefix that you specify and are subject to modification by graphite, datadog, or statsd. For example if prefix used is `openwhisk` then metric names would be like `openwhisk.counter.controller_activation_start`. This document assumes that metric name prefix is `openwhisk`

Currently OpenWhisk emits following types of metrics

#### Counter

Counter [record the count](http://kamon.io/documentation/0.6.x/kamon-core/metrics/instruments/#counters) of metric and there names are prefixed with `openwhisk.counter`. For example `openwhisk.counter.controller_activation_start`. Counters just counts and resets to zero upon each flush.

#### Histograms

Histogram record the [distribution](http://kamon.io/documentation/0.6.x/kamon-core/metrics/instruments/#histograms) of given metric and there names are prefixed with `openwhisk.histogram`. For example `openwhisk.histogram.controller_activation_finish`. A histogram metrics may result in multiple values at the metric aggregator level. For example in [Datadog](https://docs.datadoghq.com/developers/metrics/histograms/) for each histogram metric following values are record

* `my_metric.avg` - Average of aggregated values during the flush interval.
* `my_metric.count` - Count of aggregated values during the flush interval.
* `my_metric.median` - Median of aggregated values during the flush interval.
* `my_metric.95percentile` - 95th percentile value of aggregated values during the flush interval.
* `my_metric.max` - Max of aggregated values during the flush interval.
* `my_metric.min` - Min of aggregated values during the flush interval.

#### Gauges

Gauges record the [distribution](https://kamon.io/docs/latest/core/metrics/#gauges) of given metric and their names are prefixed with `openwhisk.gauge`. For example `openwhisk.gauge.loadbalancer_totalHealthyInvoker_counter`. A gauge metrics provides the value at the given point and reports the same data unless the value has been changed be incremental or decremental than before. Gauges are useful for reporting metrics like kafka queue size or disk size.

### Metric Details

Below are some of the important metrics emitted by OpenWhisk setup

#### Controller metrics

Metrics below are emitted from within a Controller instance.

##### Controller Startup

* `openwhisk.counter.controller_startup<controller_id>_counter` (counter)
  * Example _openwhisk.counter.controller_startup0_counter_
  * Records count of controller instance startup

##### Controller Activation Retrieval During Blocking Invocations

* `openwhisk.counter.controller_blockingActivationDatabaseRetrieval_counter` (counter) - Records the count of activations the controller has retrieved from the activation store during blocking invocations

##### Activation Submission

Following metrics record stats around activation handling within Controller

* Normal actions
  * `openwhisk.counter.controller_activation_start` (counter) - Records the count of non blocking activations started.
  * `openwhisk.histogram.controller_activation_finish` (histogram) - Records the overall time taken for non blocking activation to be submitted to Load balancer.
* Blocking actions
  * `openwhisk.counter.controller_blockingActivation_start` (counter) - Records the count of blocking activations started.
  * `openwhisk.histogram.controller_blockingActivation_finish` (histogram) - Records the time taken for a blocking activation to finish or timeout.

##### Load Balancer

Aggregate metrics for inflight activations.

* `openwhisk.gauge.loadbalancer<controllerId>_activationsInflight_counter` (gauge) - Records the number of activations being worked upon for a given controller. As a gauge this will give inflight activation count at the given point in time unless the change in value occurs.
* `openwhisk.gauge.loadbalancer<controllerId>_memory<invokerType>Inflight_counter` (gauge) - Records the amount of RAM memory in use for in flight activations. This is not actual runtime memory but the memory specified per action limits. **invokerType** defines whether it is a managed or a blackbox invoker.

Metrics below are for current memory capacity

* `openwhisk.histogram.loadbalancer_totalCapacity<invokerType>_counter` (histogram) - Current memory capacity for all usable managed and blackbox invokers, total user memory in shard managed by controller. **invokerType** defines whether it is a managed or a blackbox invoker.

Metrics below are captured within load balancer

* `openwhisk.counter.loadbalancer_activations_counter` (counter) -  Records the count of activations sent to Kafka.
* `openwhisk.counter.controller_kafka_start` (counter) - Records the count of activations sent to Kafka.
* `openwhisk.counter.controller_kafka_error` (counter) - Records the count of activations which encountered some failure while submitting to Kafka.
* `openwhisk.histogram.controller_kafka_finish` (histogram) - Records the time taken when activation was successfully submitted to Kafka.
* `openwhisk.histogram.controller_kafka_error` (histogram) - Records the time taken when activation submission to Kafka resulted in failure.
* `openwhisk.counter.controller_loadbalancer_start` (counter) - Records the count of activations submitted to load balancer.
* `openwhisk.histogram.controller_loadbalancer_finish` (histogram) - Records the time taken to submit to load balancer.

Metrics below are for invoker state as recorded within load balancer monitoring.

* `openwhisk.gauge.loadbalancer_totalHealthyInvoker<invokerType>_counter`(gauge) - Records the count of managed invokers considered healthy based on health pings. **invokerType** defines whether it is a managed or a blackbox invoker.
* `openwhisk.gauge.loadbalancer_totalUnresponsiveInvoker<invokerType>_counter` (gauge) - Records the count of managed invokers considered unresponsive when health pings arriving fine but the invokers do not respond with active-acks in given time. **invokerType** defines whether it is a managed or a blackbox invoker.
* `openwhisk.gauge.loadbalancer_totalOfflineInvoker<invokerType>_counter` (gauge) - Records the count of managed invokers considered offline when no health pings arrive from the invokers. **invokerType** defines whether it is a managed or a blackbox invoker.
* `openwhisk.gauge.loadbalancer_totalUnhealthyInvoker<invokerType>_counter` (gauge) - Records the count of managed invokers considered unhealthy when health pings arrive fine but the invokers report system errors. **invokerType** defines whether it is a managed or a blackbox invoker.

Metrics below provide information about completion ack processing in load balancers. Depending on configuration setting `metrics_kamon_tags` (see above), a base metric with tags or a set of metrics without tags will be emitted.

* Base metric `openwhisk.counter.loadbalancer_completionAck_counter`: count of processed regular or forced completion acks.
* Tag `controller_id`: the controller's id.
* Tag `type`: the exact type of completion ack.
  * Type `regular`: a regular completion ack sent by an invoker and received in time. Does not include completion acks for healthcheck actions.
  * Type `forced`: no completion ack was received in time and the timeout forced the completion ack to close.
  * Type `healthcheck`: a regular completion ack for healthcheck actions sent by an invoker and received in time.
  * Type `regularAfterForced`: a regular completion ack sent by an invoker and not received in time. The completion ack was already forced.
  * Type `forcedAfterRegular`: a timeout tries to force a completion ack that has already been closed by a regular completion ack. A race condition that can occur if the regular completion ack is received near the timeout.
* If `metrics_kamon_tags` is set to `false`, a set of metrics will be emitted constructed using following scheme: `openwhisk.counter.loadbalancer<controller_id>_completionAck_<type>_counter`.

#### Invoker metrics

##### Container Init

* `openwhisk.counter.invoker_activationInit_start` (counter) - Count of container initializations done.
* `openwhisk.histogram.invoker_activationInit_finish` (histogram) - Time taken for successful container initializations.
* `openwhisk.histogram.invoker_activationInit_error` (histogram) - Time taken container initialization failed. Count metrics of this histogram would give insight on failed initialization count.

##### Container Run

* `openwhisk.counter.invoker_activationRun_start` (counter) - Count of action executions performed.
* `openwhisk.histogram.invoker_activationRun_finish` (histogram) - Time taken for action execution for success case.
* `openwhisk.histogram.invoker_activationRun_error` (histogram) - Time taken for action execution for failed cases. Count metrics of this histogram would give insight on failed execution count.

##### Container Start

* `openwhisk.counter.invoker_containerStart.cold_counter` (counter) - Count of number of cold starts.
* `openwhisk.counter.invoker_containerStart.recreated_counter` (counter) - Count of number of times container is recreated.
* `openwhisk.counter.invoker_containerStart.warm_counter` (counter) - Count of number of times a warm container is used.

##### Log Collection

* `openwhisk.counter.invoker_collectLogs_start` (counter) - Count of number of times log were collected.
* `openwhisk.counter.invoker_collectLogs_error` (counter) - Count of number of failed logs collections.
* `openwhisk.histogram.invoker_collectLogs_error` (histogram) - Time taken for failed log collection.
* `openwhisk.histogram.invoker_collectLogs_finish` (histogram) - Time taken for successful log collection.

##### Activation Handling

* `openwhisk.counter.invoker_activation_start` (counter) - Count of activations handled

##### Docker Metrics

Following metrics capture stats around various docker command executions.

* pause
  * `openwhisk.counter.invoker_docker.pause_start`
  * `openwhisk.counter.invoker_docker.pause_error`
  * `openwhisk.counter.invoker_docker.pause_timeout`
  * `openwhisk.histogram.invoker_docker.pause_finish`
  * `openwhisk.histogram.invoker_docker.pause_error`
* ps
  * `openwhisk.counter.invoker_docker.ps_start`
  * `openwhisk.counter.invoker_docker.ps_error`
  * `openwhisk.counter.invoker_docker.ps_timeout`
  * `openwhisk.histogram.invoker_docker.ps_finish`
  * `openwhisk.histogram.invoker_docker.ps_error`
* pull
  * `openwhisk.counter.invoker_docker.pull_start`
  * `openwhisk.counter.invoker_docker.pull_error`
  * `openwhisk.counter.invoker_docker.pull_timeout`
  * `openwhisk.histogram.invoker_docker.pull_finish`
  * `openwhisk.histogram.invoker_docker.pull_error`
* rm
  * `openwhisk.counter.invoker_docker.rm_start`
  * `openwhisk.counter.invoker_docker.rm_error`
  * `openwhisk.counter.invoker_docker.rm_timeout`
  * `openwhisk.histogram.invoker_docker.rm_finish`
  * `openwhisk.histogram.invoker_docker.rm_error`
* run
  * `openwhisk.counter.invoker_docker.run_start`
  * `openwhisk.counter.invoker_docker.run_error`
  * `openwhisk.counter.invoker_docker.run_timeout`
  * `openwhisk.histogram.invoker_docker.run_finish`
  * `openwhisk.histogram.invoker_docker.run_error`
* unpause
  * `openwhisk.counter.invoker_docker.unpause_start`
  * `openwhisk.counter.invoker_docker.unpause_error`
  * `openwhisk.counter.invoker_docker.unpause_timeout`
  * `openwhisk.histogram.invoker_docker.unpause_finish`
  * `openwhisk.histogram.invoker_docker.unpause_error`

#### Kafka Metrics

Metrics below are emitted per kafka topic.

* `openwhisk.histogram.kafka_<topic name>.delay_start` - Time delay between when a message was pushed to Kafka and when it is read within a consumer. This metric is recorded for every message read.
* `openwhisk.gauge.kafka_<topic name>_counter` - Records the Queue size of the topic. By default this metric is emitted every 60 secs.

Metrics per topic
* `cacheInvalidation` - Emitted per controller while reading the cache invalidation messages.
  * `openwhisk.histogram.kafka_cacheInvalidation.delay_start`
  * `openwhisk.histogram.kafka_cacheInvalidation_counter.count`
* `health` - Emitted per controller while reading the invoker health pings.
  * `openwhisk.histogram.kafka_health.delay_start`
  * `openwhisk.histogram.kafka_health_counter`
* `completed<controllerId>` - Topic to receive completed activations. This is emitted per controller for its own topic. For example for controller id 0 metric names would be
  * `openwhisk.histogram.kafka_completed0.delay_start`
  * `openwhisk.histogram.kafka_completed0_counter`
* `invoker<invokerId>` - Topic to receive activations to complete. This is emitted per invoker for its own topic. For example for invoker id 0 metric names would be
  * `openwhisk.histogram.kafka_invoker0_counter`
  * `openwhisk.histogram.kafka_invoker0.delay_start`

#### Database Metrics

##### Cache Metrics

* `openwhisk.counter.database_cacheHit_counter` - Count of cache hits.
* `openwhisk.counter.database_cacheMiss_counter` - Count of cache misses.

Metrics below are emitted for database related operations and follow a pattern

* `openwhisk.counter.database_<operation type>_start` - Count of database operations done for given type. Example `openwhisk.counter.database_getDocument_start`.
* `openwhisk.counter.database_<operation type>_error` - Count of database operations done for given type which resulted in error. Example `openwhisk.counter.database_getDocument_error`.
* `openwhisk.histogram.database_<operation type>_finish` - Time taken for successful completion of given database operation. Example `openwhisk.histogram.database_getDocument_finish`.
* `openwhisk.histogram.database_<operation type>_error` - Time taken for failed completion of given database operation. Example `openwhisk.histogram.database_getDocument_error`.

Operation Types

* `deleteDocument`
* `getDocument`
* `queryView`
* `saveDocument`
* `saveDocumentBulk`

#### CosmosDB RU Metrics

When database used is CosmosDB then metrics related to CosmosDB Resource Units is also emitted.

If Kamon tags are enabled then metric name is `openwhisk.counter.cosmosdb_ru_used` with following tags

- `mode` - `read` or `write`
- `collection` - Name of collection. Example `activations`, `whisks` and `subjects`
- `action` - Type of operation performed. Example `get`, `put`, `del`, `query` and `count`

If Kamon tags are not enabled then metric name is of the form `openwhisk.counter.cosmosdb.ru.<collection>.<action>`

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
causedBy - contains the "causedBy" annotation (can be "sequence" or nothing at the moment)
size - size (in bytes) of the invocation response
userDefinedStatusCode - status code represents `statusCode` set in result response. (if not set, this field will not be present)
```
Metric is any user specific event produced by the system and it at this moment includes the following information:
```
ConcurrentRateLimit - a user has exceeded its limit for concurrent invocations.
TimedRateLimit - the user has reached its per minute limit for the number of invocations.
ConcurrentInvocations - the number of in flight invocations per user.
```

Example events that could be consumed from Kafka.
Activation:
```json
{
  "body": {
    "statusCode": 0,
    "duration": 3,
    "name": "whisk.system/invokerHealthTestAction0",
    "waitTime": 583915671,
    "conductor": false,
    "kind": "nodejs:6",
    "initTime": 0,
    "memory": 256,
    "size": 463,
    "causedBy": false
  },
  "eventType": "Activation",
  "source": "invoker0",
  "subject": "whisk.system",
  "timestamp": 1524476122676,
  "userId": "d0888ad5-5a92-435e-888a-d55a92935e54",
  "namespace": "whisk.system"
}
```
Metric:
```json
{
  "body": {
    "metricName": "ConcurrentInvocations",
    "metricValue": 1
  },
  "eventType": "Metric",
  "source": "controller0",
  "subject": "guest",
  "timestamp": 1524476104419,
  "userId": "23bc46b1-71f6-4ed5-8c54-816aa4f8c502",
  "namespace": "guest"
}
```

### User-events consumer service
All user metrics can be consumed and published to various services such as Prometheus, Datadog etc via Kamon by using the [user-events service](https://github.com/apache/openwhisk/tree/master/core/monitoring/user-events/README.md).

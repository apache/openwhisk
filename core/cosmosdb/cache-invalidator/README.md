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
# OpenWhisk Cache Invalidator Service

This service performs cache invalidation in OpenWhisk cluster to enable cache event propagation in multi region setups.

## Design

OpenWhisk cluster uses a Kafka topic `cacheInvalidation` to communicate changes to any cached entity. Messages on this
topic are of the form

```json
{"instanceId":"controller0","key":{"mainId":"guest/hello"}}
```

When deploying multiple seprate cluster of OpenWhisk which do not share same Kafka instance we would need a way to
propagate the cache change event across cluster. For CosmosDB based setups this can be done by using [CosmosDB ChangeFeed][1]
support. It enables reading changes that are made to any specific collection.

This service makes use of [change feed processor java][2] library and listen to changes happening in `whisks` and `subject`
collection and then convert them into Kafka message events which can be sent to `cacheInvalidation` topic of local cluster

## Usage

The service needs following env variables to be set

- `KAFKA_HOSTS` - For local env it can be set to `172.17.0.1:9093`. When using [OpenWhisk Devtools][3] based setup use `kafka`
- `COSMOSDB_ENDPOINT` - Endpoint URL like https://<account>.documents.azure.com:443/
- `COSMOSDB_KEY` - DB Key
- `COSMOSDB_NAME` - DB name

Upon startup it would create a collection to manage the lease data with name `cache-invalidator-lease`. For events sent by
this service `instanceId` would be set to `cache-invalidator`

## Local Run

Setup the OpenWhisk cluster using [devtools][3] but have it connect to CosmosDB. This would also start
the [Kafka Topic UI][4] at port `8001`. Then if any change is made to db then you would see events set to Kafka topic like

For e.g. if any package is created like `wsk package create test-package` using `guest` account then following event can
be seen

```json
 {"instanceId":"cache-invalidator","key":{"mainId":"guest/test-package"}}
```


[1]: https://docs.microsoft.com/en-us/azure/cosmos-db/change-feed
[2]: https://github.com/Azure/azure-documentdb-changefeedprocessor-java
[3]: https://github.com/apache/incubator-openwhisk-devtools/tree/master/docker-compose
[4]: https://github.com/Landoop/kafka-topics-ui

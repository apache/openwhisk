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

Fluent Bit for user action log enrichment
================

This directory builds a Docker image with Fluent Bit configured to
ingest a directory of user logs via its 'Tail' input plugin and enrich
the logs by adding the activationId and namespace to each record. The
image can be configured to forward these enriched logs to a variety of
upstream log sinks, including fluentd and elastic search.  The Tail
plugin understands non-truncating log rotation, so log rotation for
user containers can be simply delegated to a standard utility like
logrotate installed on the underlying platform.

The log processing will only enrich logs that conform to the
convention that the logs from each activation are fully framed with
start and end sentinels as described in the Logs subsection of
[actions-new.md](../../docs/actions-new.md).  Logs from all
non-conformant containers are dropped.

A typical usage of this docker image is to deploy it as a DaemonSet on
all invoker nodes in a Kubernetes deployment of OpenWhisk.  The
directory containing the user logs is mounted into the container at
/input-logs. As user actions run and generate logs into this
directory, fluent-bit will ingest them, enrich them with the
activationId and namespace and forward them to the platform logging
service (eg forwarded to the platform's fluentd instance on that node).
Invokers are configured to use the LogDriverLogStore (which do
nothing), since the log processing/enrichment is handled externally by
Fluent Bit.

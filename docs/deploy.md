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
This page documents configuration options that should be considered when deploying OpenWhisk. Some deployment options require special treatment wrt to the underlying infrastructure/deployment model. Please carefully read about the constraints before you decide to enable them.

# Controller Clustering

The system can be configured to use Akka clustering to manage the distributed state of the Controller's load balancing algorithm.  This imposes the following constraints on a deployment

## Cluster setup

To setup a cluster, the controllers need to be able to discover each other. There are 2 basic ways to achieve this:

1. Provide the so called **seed-nodes** explicitly on deployment. Essentially you have a static list of possible seed nodes which are used to build a cluster. In an Ansible based deployment, they are determined for you from the `hosts` file. On any other deployment model, the `CONFIG_akka_cluster_seedNodes.$i` variables will need to be provided according to the [Akka cluster documentation](https://doc.akka.io/docs/akka/2.5/cluster-usage.html#joining-to-seed-nodes).
2. Discover the nodes from an external service. This is built upon [akka-management](https://developer.lightbend.com/docs/akka-management/current/) and by default [Kubernetes](https://developer.lightbend.com/docs/akka-management/current/discovery.html#discovery-method-kubernetes-api) and [Mesos (Marathon)](https://developer.lightbend.com/docs/akka-management/current/discovery.html#discovery-method-marathon-api) are supported. You can refer to the respective documentation above to configure discovery accordingly.


## Controller nodes must have static IPs/Port combination.

It guarantees that failed nodes are able to join the cluster again.
This limitation refers to the fact that Akka clustering doesn't allow to add new nodes when one of the existing members is unreachable (e.g. JVM failure). If each container receives a its ip and port dynamically upon the restart, in case of controller failure, it could come back online under a new ip/port combination which makes cluster consider it as a new member and it won't be added to the cluster (in certain cases it could join as a weeklyUp member). However, the cluster will still replicate the state across the online nodes, it will have trouble to get back to the previous state with desired number of members until the old member is explicitly "downed".

How to down the members.
1. manually (sending an HTTP or JMX request to the controller). For this case an external supervisor for the cluster is required, which will down the nodes and provide an up-to-date list of seed nodes.
2. automatically by setting the "auto-down-property" in controller that will allow the leader to down the node after a certain timeout. In order to mitigate brain split one could define a list of seed nodes which are reachable under static IPs or have static DNS entries.

Link to Akka clustering documentation:
https://doc.akka.io/docs/akka/2.5.4/scala/cluster-usage.html

## Shared state vs. Sharding

OpenWhisk used to support both shared state and a sharding model. The former has since been deprecated and removed.

The sharding loadbalancer has the caveat of being limited in its scalability in its current implementation. It uses "horizontal" sharding, which means that the slots on each invoker are evenly divided to the loadbalancers. For example: In a system with 2 loadbalancers and invokers which have 16 slots each, each loadbalancer would get 8 slots on each invoker. In this specific case, a cluster of loadbalancers > 16 instances does not make sense, since each loadbalancer would only have a fraction of a slot above that. The code guards against that but it is strongly recommended not to deploy more sharding loadbalancers than there are slots on each invoker.

# Invoker use of docker-runc

To improve performance, Invokers attempt to maintain warm containers for frequently executed actions. To optimize resource usage, the action containers are paused/unpaused between invocations.  The system can be configured to use either docker-runc or docker to perform the pause/unpause operations by setting the value of the environment variable INVOKER_USE_RUNC to true or false respectively. If not set, it will default to true (use docker-runc).

Using docker-runc obtains significantly better performance, but requires that the version of docker-runc within the invoker container is an exact version match to the docker-runc of the host environment.  Failure to get an exact version match will results in error messages like:
```
2017-09-29T20:15:54.551Z] [ERROR] [#sid_102] [RuncClient] code: 1, stdout: , stderr: json: cannot unmarshal object into Go value of type []string [marker:invoker_runc.pause_error:6830148:259]
```
When a docker-runc operations results in an error, the container will be killed by the invoker.  This results in missed opportunities for container reuse and poor performance.  Setting INVOKER_USE_RUNC to false can be used as a workaround until proper usage of docker-runc can be configured for the deployment.

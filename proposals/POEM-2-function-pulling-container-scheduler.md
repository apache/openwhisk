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

# Title
Function Pulling Container Scheduler (FPCScheduler)

## Status
* Current state: In-progress
* Author(s): @style95, @ningyougang, @keonhee, @jiangpengcheng, @upgle

## Summary and Motivation

This POEM proposes a new scheduler for OpenWhisk, FPCScheduler.
Previously we revealed [performance issues](https://cwiki.apache.org/confluence/display/OPENWHISK/Autonomous+Container+Scheduling+v1) in OpenWhisk.
There are many reasons for them, but we can summarize them into the following problems.

1. Multiple actions share the same `homeInvoker`.
2. Current scheduler does not consider the time taken for container operations (create/delete, pause/unpause).
3. Resources are evenly divided by the number of controllers.

First, multiple actions share the same `homeInvoker`. This is because the `homeInvoker` is statically decided by a hash function.
While scheduling, resource status in the invoker side is not considered and this introduces busy hotspot invokers while the others are idle.
This is generally a good idea to increase the probability of container reuse, but it leads to performance degradation because of slow container operation.
When all resources in an invoker are taken by one action (busy/warm), an activation for another action will remove one of the existing containers and create a new one for it.
If actions run for a long time this would be a good approach in terms of bin-packing. But when actions run for short time (most of the serverless cases),
the heuristic severely increases the response time because container operations take one to two orders of magnitude longer time than invocation and this results in poor performance.

Second, since controllers schedule activations to invokers in a distributed environment, the invokers in the system are partitioned and assigned to specific controllers. This means that any one controller is assigned a fraction of the available resources in the system.
It's not feasible to collect the status of all invokers so that scheduling decisions have a global view.
As a result, controllers may throttle activations even if cluster-wide invocations are below limits (and when there are available resources and system capacity).

We propose FPCScheduler to address the above issues.
The scheduler differs from the existing scheduler(`ShardingPoolBalancer`) in the following ways:
- It schedules containers rather than function requests. Each container will pull activations requests from the given action queue continuously.
- Whenever one execution is over, it fetches the next activation requests and invokes it repeatedly. In this way, we can maximize container reuse.
- An added benefit is that schedulers don't need to track or consider the location of existing containers. Schedulers decide where and when to create more containers.
- The scheduler can create more containers on invokers with enough resources. This enables distributed scheduling with all invoker resources among multiple schedulers.

Controllers no longer schedule activation requests. Instead, controllers create action queues and forward activation requests to these queues.
Each action queue is a dedicated queue for the given action and dynamically created/deleted by the FPCSchedulers. Each action has its own queue, so there is no interference among actions because of a shared queue.

[ETCD](https://github.com/etcd-io/etcd), a distributed and reliable key-value store is used for a transaction, health-check, cluster information sharing, etc.
Each scheduler performs a transaction via ETCD when scheduling. The health status of each component(scheduler, invoker) is managed by a [lease](https://help.compose.com/docs/etcd-using-etcd3-features#leases) in ETCD.
Whenever a component is failed, it no longer sends keepalive requests, and its health data is removed via a lease timing out.
Cluster-wide information such as scheduler endpoints, queue endpoints, containers in a namespace, and throttling is stored in ETCD and referenced by corresponding components.
Controllers throttle namespaces based on the throttling data in ETCD. So all controllers share the same view against the resources and manage them in the same way.

One more benefit of having our own component for routing rather than utilizing open-source components such as Kafka is we can extend and implement any routing logic.
We would want various routing policies at some point. For example, when we utilize multiple versions of an action in-flight in the future we might want to control the traffic ratio between the two versions,
we can route activations only to invokers with a specific resource, we may want to have dedicated invokers for some namespaces and so on. And this POEM could be a baseline for such extensions.

## Proposed changes: Architecture Diagram (optional) and Design
The design document along architecture diagram is already shared on the [OpenWhisk Wiki](https://cwiki.apache.org/confluence/display/OPENWHISK/Apache+OpenWhisk+Project+Wiki?src=sidebar)

* [Design Consideration](https://cwiki.apache.org/confluence/display/OPENWHISK/Design+consideration?src=contextnavpagetreemode)
* [Architecture](https://cwiki.apache.org/confluence/display/OPENWHISK/System+Architecture)
* [Component Design](https://cwiki.apache.org/confluence/display/OPENWHISK/Component+Design)

### Implementation details

For the record, we (NAVER) are already operating OpenWhisk with this scheduler in a production environment.
We want to contribute the scheduler and hope it evolves with the community.

There are many [new components](https://cwiki.apache.org/confluence/display/OPENWHISK/Component+Design).

#### Common components
We store data in ETCD, there are many relevant components such as `EtcdClient`, `LeaseKeepAliveService`, `WatcherService`, `DataManagementService`, etc.

* `EtcdClient`: An ETCD client offering basic CRUD operations.
* `DataManagementService`: In charge of storing/recovering data in ETCD. It internally utilizes `LeaseKeepAliveService`, and `WatcherService`.
* `LeaseKeepAliveService`: In charge of keeping the given lease alive.
* `WatcherService`: Watches the given keys and receives events for the keys.

#### Scheduler components

In an abstract view, schedulers provide queueing, container scheduling, and activation routing.

* `QueueManager`: The main entry point for queue creation request. It has references to all queues.
* `MemoryQueue`: Dynamically created/deleted for each action. It watches the incoming/outgoing requests and triggers container creation.
* `ContainerManager`: Schedule container creation requests to appropriate invokers.
* `ActivationServiceImpl`: Provide API for containers to fetch activations via Akka-grpc. It works in a long-poll way to avoid busy-waiting.

#### Controller components
* `FPCPoolBalancer`: Create queues if not exist, and forward messages to them.
* `FPCEntitlementProvider`: Throttle activations based on throttling information in ETCD.

#### Invoker components

* `FunctionPullingContainerPool`: A container pool for function pulling container. It handles the container creation requests.
* `FunctionPullingContainerProxy`: A proxy for a container. It repeatedly fetches activations and invokes them.
* `ActivationClientProxy`: The Akka-grpc client. It communicates with `ActivationServiceImpl` in schedulers.
* `InvokerHealthManager`: Manages the health and resources information of invokers. The data is stored in ETCD. If an invoker becomes unhealthy, it invokes health activations.

## Future work

#### Persistence

We reached a conclusion that the persistence of activation requests is not that mandatory requirement along with the at-most-once nature and circuit-breaking of OpenWhisk.
But if it is desired, we can implement a persistent queue rather than an in-memory queue.

#### Multiple partitions

Currently, a queue only has one partition. Since there can be multiple queues for each action, cluster-wide RPS(request per second) increases linearly.
If high RPS for one action is required, we can implement partitioning in queues to introduce parallel processing like what Kafka does.
But we already confirmed 100K RPS with 10 queues and 10 commodity invokers. And the performance would linearly increase with more invokers and queues.

## Integration and Migration plan

Since they are all new components and can coexist with existing components using SPI(Service Provider Interface), there would be no breaking change.
We would incrementally merge PRs into the master branch.

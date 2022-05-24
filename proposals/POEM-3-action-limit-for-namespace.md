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
Providing action limits for each namespace

## Status
* Current state: In-progress
* Author(s): @upgle

## Summary and Motivation

This POEM proposes a new feature that allows administrators to set action limits (memory, timeout, log, and concurrency) for each namespace.

Sometimes some users want to make an action with more memory and longer duration. But, OpenWhisk only has a system limit for action shared by all namespaces.
There is no way to adjust the action limit for a few users, and changing the action limit setting will affect all users.

In some private environments, you can operate OpenWhisk more flexibly by providing different action limits.
(For example, providing high memory only to some users.)

```
          256M                               512M

            │     namespace default limit      │
            ▼                                  ▼
 ┌──────────┬──────────────────────────────────┬────────────┬────────────────────────┐
 │          │┼┼┼┼┼┼┼┼┼┼┼┼┼┼┼┼┼┼┼┼┼┼┼┼┼┼┼┼┼┼┼┼┼┼│----------► │                        │
 └──────────┴──────────────────────────────────┴────────────┴────────────────────────┘
 ▲                                                          ▲                        ▲
 │ system limit                                             │ namespace limit        │ system limit

128M                                                      1024M                    2048M
```

## Proposed changes

### 3 types of action limits

There was only a system limit shared by all namespaces, but two more concepts for namespace limits are added.

- (1) system limit: It can never be exceeded under any circumstances.
- (2) namespace default limit: It can be used if a limit has not been set for a namespace.
- (3) namespace limit: It can be set by a system administrator for a namespace and cannot exceed the range of the system limit.

### Limit configs for namespace

- The maxParameterSize, maxPayloadSize and truncationSize values are treated as `ByteSize` string. (e.g. 1 MB, 512 KB...)

The following settings are new:

config key             | Type                         | description
---------------------- | ---------------------------- | ---------------
minActionMemory        | integer (unit: MB)           | minimum action memory size for namespace
maxActionMemory        | integer (unit: MB)           | maximum action memory size for namespace
minActionLogs          | integer (unit: MB)           | minimum activation log size for namespace
maxActionLogs          | integer (unit: MB)           | maximum activation log size for namespace
minActionTimeout       | integer (unit: milliseconds) | minimum action time limit for namespace
maxActionTimeout       | integer (unit: milliseconds) | maximum action time limit for namespace
minActionConcurrency   | integer                      | minimum action concurrency limit for namespace
maxActionConcurrency   | integer                      | maximum action concurrency limit for namespace
maxParameterSize       | string  (format: ByteSize)   | maximum parameter size for namespace
maxPayloadSize         | string  (format: ByteSize)   | maximum payload size for namespace
truncationSize         | string  (format: ByteSize)   | activation truncation size for namespace


### Limit document for CouchDB

You can set namespace limits with `{namespace}/limits` document just like any other existing settings (e.g., invocationsPerMinute, concurrentInvocations).

```json
{
  "concurrentInvocations": 100,
  "invocationsPerMinute": 100,
  "firesPerMinute": 100,
  "maxActionMemory": 1024,
  "minActionMemory": 128,
  "maxActionConcurrency": 400,
  "minActionConcurrency": 1,
  "maxActionLogs": 128,
  "minActionLogs": 0,
  "maxParameterSize": "1048576 B"
}
```

#### Applying namespace limit
- Because there is no administrator API, you must modify the DB directly or use the wskadmin tool.
- There is plan to provide the feauture to change namespace limits in wskadmin.

### Namespace Limit API

User can get the applied action limits of the namespace by the namespace limit API.
If the namespace's action limit is not set, the default namespace limit value will be returned.

> GET /api/v1/namespaces/_/limits

```json
{
  "concurrentInvocations": 30,
  "firesPerMinute": 60,
  "invocationsPerMinute": 60,
  "maxActionConcurrency": 500,
  "maxActionLogs": 0,
  "maxActionMemory": 512,
  "maxActionTimeout": 300000,
  "maxParameterSize": "1048576 B",
  "minActionConcurrency": 1,
  "minActionLogs": 0,
  "minActionMemory": 128,
  "minActionTimeout": 100
}
```

### System API (URI path: /)

A namespace default limit information is additionally provided separately from the previously provided system limit information.

- default_max_action_duration
- default_max_action_logs
- default_max_action_memory
- default_min_action_duration
- default_min_action_logs
- default_min_action_memory

#### Preview

> GET /

```json
{
  "api_paths": [
    "/api/v1"
  ],
  "description": "OpenWhisk",
  "limits" : {
    "actions_per_minute": 60,
    "concurrent_actions": 30,
    "default_max_action_duration": 300000,
    "default_max_action_logs": 0,
    "default_max_action_memory": 536870912,
    "default_min_action_duration": 100,
    "default_min_action_logs": 0,
    "default_min_action_memory": 134217728,
    "max_action_duration": 300000,
    "max_action_logs": 0,
    "max_action_memory": 536870912,
    "min_action_duration": 100,
    "min_action_logs": 0,
    "min_action_memory": 134217728,
    "sequence_length": 50,
    "triggers_per_minute": 60
  }
}
```

### Backward compatibility

For backward compatibility, if there is no namespace default limit setting, it is replaced with a system limit.

As the namespace default limit is the same as the system limit, so the administrator cannot set the namespace limit, and the user can create actions with resources (memory, logs, timeout...) up to the system limit as before.


### Namespace limit validation

Previously, system limits were validated when deserializing the `ActionLimits` object from the user request. 

However, at the time of deserialization of the user requests, the namespace's action limit cannot be known and the limit value cannot be included in an error message, so the validation must be performed after deserialization.
Therefore, the code to perform this validation has been added to the controller, scheduler, and invoker.

#### 1. Validate action limits when the action is created in the controller

When an action is created in the controller, make sure that the action limits do not exceed the system limits and namespace limits.

If the namespace limits or system limits are exceeded, the namespace limit value must be returned as an error message in the response body.

```
                                                 ┌───────────────┐
                                                 │               │
                                                 │   AuthStore   │
                                                 │               │
                                                 └───────┬───────┘
                                                         │
                                                 ┌───────┴───────┐
                                                 │               │
                                                 │   Identity    │ UserLimits
                                                 │               │ (maxActionMemory = 512M)
  Create action     ┌───────────────────┐        └───────────────┘
 (memory = 1024M)   │                   │                ▲
──────────────────► │                   │                │
                    │    Controller     ├────────────────┘
◄────────X───────── │                   │   Validate namespace limit
   Reject request   │                   │
   (1024M > 512M)   └───────────────────┘
```


#### 2. Validate action limits when the action is executed in the invoker

When the action is executed, the invoker must checks whether the action limit exceeds the system limit and namespace limits. 
If the limit of the action to be executed exceeds the limit, an application error with `Messages.actionLimitExceeded` message is returned and invocation is aborted.

```scala
case _: ActionLimitsException =>
  ActivationResponse.applicationError(Messages.actionLimitExceeded)
```

```
                                                             ┌───────────────┐
                                                             │               │
                                                             │   Identity    │  UserLimits
                                                             │               │  (maxActionMemory = 512M)
                                                             └───────────────┘
                                                                  ▲
                                                                  │  Validate namespace limit
                                                                  │
  Invoke action     ┌───────────────────┐     Activation     ┌────┴──────────────┐
 (memory = 1024M)   │                   │       Message      │                   │
──────────────────► │                   │ ─────────────────► │                   │
                    │    Controller     │                    │      Invoker      │
◄────────X───────── │                   │ ◄────────X──────── │                   │
   Reject request   │                   │        Reject      │                   │
                    └───────────────────┘      Invocation    └───────────────────┘
                                             (1024M > 512M)
```

#### 3. Validate action limits when the action is executed in the invoker with the scheduler

The invoker that works with the scheduler should check namespace limits when creating containers and handling activations.

- When creating a container, if the requested resource of the action exceeds the namespace limit, creation is rejected and the queue is removed.
- when processing an activation message, if the action exceeds the namespace limit, the activation is rejected.


```
                                                         ┌───────────────┐
                                                         │               │
                                                         │   Identity    │ UserLimits
                                                         │               │ (maxActionMemory = 512M)
                                                         └───────────────┘
                                                                 ▲
                                        Invoker                  │
                                       ┌─────────────────────────┼─┐
┌─────────────┐   ContainerCreation    │                         │ │
│             │        Message         │  ┌────────────────────┐ │ │
│             │ ───────────────────────┼─►│  ContainerMessage  │ │ │
│             │                        │  │     Consumer       ├─┤ │ Validate namespace limit
│             │ ◄───────────X──────────┼─ └────────────────────┘ │ │
│  Scheduler  │     Reject creating    │                         │ │
│             │        container       │  ┌────────────────────┐ │ │
│             │                        │  │  FunctionPulling   │ │ │
│             │ ◄──────────────────────┼──┤  ContainerProxy    ├─┘ │
│             │      Fetch activation  │  └──────────────┬─────┘   │
└─────────────┘                        │                 │         │
                                       └─────────────────┼─────────┘
                    Kafka                                │
                   ┌───────────────┐                     │
                   ├───────────────┤                     │
                   │ Completed0    │ ◄─────────X─────────┘
                   ├───────────────┤   Activation Response
                   └───────────────┘    (Reject 1024>512M)
```



### Handling invalid namespace limits

Because there is no admin API to handle namespace limits, the CouchDB document may have namespace limit values that exceed the system limits.
But, If there is a namespace limit that exceeds the system limit, the namespace limit is lowered to the system limit.

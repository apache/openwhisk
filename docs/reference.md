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

# OpenWhisk system details

The following sections provide more details about the OpenWhisk system.

## OpenWhisk entities

### Namespaces and packages

OpenWhisk actions, triggers, and rules belong in a namespace, and optionally a package.

Packages can contain actions and feeds. A package cannot contain another package, so package nesting is not allowed. Also, entities do not have to be contained in a package.

In IBM Cloud, an organization+space pair corresponds to a OpenWhisk namespace. For example, the organization `BobsOrg` and space `dev` would correspond to the OpenWhisk namespace `/BobsOrg_dev`.

You can create your own namespaces if you're entitled to do so. The `/whisk.system` namespace is reserved for entities that are distributed with the OpenWhisk system.


### Fully qualified names

The fully qualified name of an entity is
`/namespaceName[/packageName]/entityName`. Notice that `/` is used to delimit namespaces, packages, and entities.

If the fully qualified name has three parts:
`/namespaceName/packageName/entityName`, then the namespace can be entered without a prefixed `/`; otherwise, namespaces must be prefixed with a `/`.

For convenience, the namespace can be left off if it is the user's *default namespace*.

For example, consider a user whose default namespace is `/myOrg`. Following are examples of the fully qualified names of a number of entities and their aliases.

| Fully qualified name | Alias | Namespace | Package | Name |
| --- | --- | --- | --- | --- |
| `/whisk.system/cloudant/read` |  | `/whisk.system` | `cloudant` | `read` |
| `/myOrg/video/transcode` | `video/transcode` | `/myOrg` | `video` | `transcode` |
| `/myOrg/filter` | `filter` | `/myOrg` |  | `filter` |

You will be using this naming scheme when you use the OpenWhisk CLI, among other places.

### Entity names

The names of all entities, including actions, triggers, rules, packages, and namespaces, are a sequence of characters that follow the following format:

* The first character must be an alphanumeric character, or an underscore.
* The subsequent characters can be alphanumeric, spaces, or any of the following: `_`, `@`, `.`, `-`.
* The last character can't be a space.

More precisely, a name must match the following regular expression (expressed with Java metacharacter syntax): `\A([\w]|[\w][\w@ .-]*[\w@.-]+)\z`.

## System limits

### Actions
OpenWhisk has a few system limits, including how much memory an action can use and how many action invocations are allowed per minute.

**Note:** This default limits are for the open source distribution; production deployments like IBM Cloud Functions likely have higher limits.
As an operator or developer you can change some of the limits using [Ansible inventory variables](../ansible/README.md#changing-limits).

The following table lists the default limits for actions.

| limit | description | configurable | unit | default |
| ----- | ----------- | ------------ | -----| ------- |
| timeout | a container is not allowed to run longer than N milliseconds | per action |  milliseconds | 60000 |
| memory | a container is not allowed to allocate more than N MB of memory | per action | MB | 256 |
| logs | a container is not allowed to write more than N MB to stdout | per action | MB | 10 |
| concurrent | no more than N activations may be submitted per namespace either executing or queued for execution | per namespace | number | 100 |
| minuteRate | no more than N activations may be submitted per namespace per minute | per namespace | number | 120 |
| codeSize | the maximum size of the action code | configurable, limit per action | MB | 48 |
| parameters | the maximum size of the parameters that can be attached | not configurable, limit per action/package/trigger | MB | 1 |
| result | the maximum size of the action result | not configurable, limit per action | MB | 1 |

### Per action timeout (ms) (Default: 60s)
* The timeout limit N is in the range [100ms..300000ms] and is set per action in milliseconds.
* A user can change the limit when creating the action.
* A container that runs longer than N milliseconds is terminated.

### Per action memory (MB) (Default: 256MB)
* The memory limit M is in the range from [128MB..512MB] and is set per action in MB.
* A user can change the limit when creating the action.
* A container cannot have more memory allocated than the limit.

### Per action logs (MB) (Default: 10MB)
* The log limit N is in the range [0MB..10MB] and is set per action.
* A user can change the limit when creating or updating the action.
* Logs that exceed the set limit are truncated and a warning is added as the last output of the activation to indicate that the activation exceeded the set log limit.

### Per action artifact (MB) (Default: 48MB)
* The maximum code size for the action is 48MB.
* It is recommended for a JavaScript action to use a tool to concatenate all source code including dependencies into a single bundled file.

### Per activation payload size (MB) (Fixed: 1MB)
* The maximum POST content size plus any curried parameters for an action invocation or trigger firing is 1MB.

### Per activation result size (MB) (Fixed: 1MB)
* The maximum size of a result returned from an action is 1MB.

### Per namespace concurrent invocation (Default: 100)
* The number of activations that are either executing or queued for execution for a namespace cannot exceed 100.
* A user is currently not able to change the limits.

### Invocations per minute (Fixed: 120)
* The rate limit N is set to 120 and limits the number of action invocations in one minute windows.
* A user cannot change this limit when creating the action.
* A CLI or API call that exceeds this limit receives an error code corresponding to HTTP status code `429: TOO MANY REQUESTS`.

### Size of the parameters (Fixed: 1MB)
* The size limit for the parameters on creating or updating of an action/package/trigger is 1MB.
* The limit cannot be changed by the user.
* An entity with too big parameters will be rejected on trying to create or update it.

### Per Docker action open files ulimit (Fixed: 1024:1024)
* The maximum number of open files is 1024 (for both hard and soft limits).
* The docker run command use the argument `--ulimit nofile=1024:1024`.
* For more information about the ulimit for open files see the [docker run](https://docs.docker.com/engine/reference/commandline/run) documentation.

### Per Docker action processes ulimit (Fixed: 1024)
* The maximum number of processes available to the action container is 1024.
* The docker run command use the argument `--pids-limit 1024`.
* For more information about the ulimit for maximum number of processes see the [docker run](https://docs.docker.com/engine/reference/commandline/run) documentation.

### Triggers

Triggers are subject to a firing rate per minute as documented in the table below.

| limit | description | configurable | unit | default |
| ----- | ----------- | ------------ | -----| ------- |
| minuteRate | no more than N triggers may be fired per namespace per minute | per user | number | 60 |

### Triggers per minute (Fixed: 60)
* The rate limit N is set to 60 and limits the number of triggers that may be fired in one minute windows.
* A user cannot change this limit when creating the trigger.
* A CLI or API call that exceeds this limit receives an error code corresponding to HTTP status code `429: TOO MANY REQUESTS`.

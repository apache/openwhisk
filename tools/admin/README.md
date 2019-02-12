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

## Administrative Operations

The `wskadmin` utility is handy for performing various administrative operations against an OpenWhisk deployment.
It allows you to create a new subject, manage their namespaces, to block a subject or delete their record entirely.
It also offers a convenient way to dump the asset, activation or subject databases and query their views. This is useful for debugging local deployments.
Lastly, it is a convenient way to inspect the system logs, or retrieve logs specific to a component or transaction id.

### Managing Users (subjects)

The `wskadmin user -h` command will print the help message for working with subject records. You can create and delete a new user, list all their namespaces or keys for a specific namespace, identify a user by their key, block/unblock a subject, and list all keys that have access to a particular namespace.

Some examples:
```bash
# create a new user
$ wskadmin user create userA
<prints key>

# add user to a specific namespace
$ wskadmin user create userA -ns space1
<prints new key specific to userA and space1>

# add second user to same space
$ wskadmin user create userB -ns space1
<prints new key specific to userB and space1>

# list all users sharing a space
$ wskadmin user list space1 -a
<key for userA>   userA
<key for userB>   userB

# remove user access to a namespace
$ wskadmin user delete userB -ns space1
Namespace deleted

# get key for userA default namespaces
$ wskadmin user get userA
<prints key specific to userA default namespace>

# block a user
$ wskadmin user block userA
"userA" blocked successfully

# unblock a user
$ wskadmin user unblock userA
"userA" unblocked successfully

# delete user
$ wskadmin user delete userB
Subject deleted
```

The `wskadmin limits` commands allow you set action and trigger throttles per namespace.

```bash
# see if custom limits are set for a namespace
$ wskadmin limits get space1
No limits found, default system limits apply

# set limits on invocationsPerMinute
$ wskadmin limits set space1 --invocationsPerMinute 1
Limits successfully set for "space1"

# set limits on allowedKinds
$ wskadmin limits set space1 --allowedKinds nodejs:6 python
Limits successfully set for "space1"

# set limits to disable saving of activations in activationstore
$ wskadmin limits set space1 --storeActivations false
Limits successfully set for "space1"
```

Note that limits apply to a namespace and will survive even if all users that share a namespace are deleted. You must manually delete them.
```bash
$ wskadmin limits delete space1
Limits deleted
```

### Inspecting the Databases

It is sometimes handy to inspect the database form the command line. The `wskadmin db get -h` command will print the help message for available utilities.
You can read the entire database with `wskadmin db get whisks`. Add `--docs` to include the full documents. To list specific views, use the `--view` option.
For example `wskadmin db get whisks --view whisks.v2/actions` will list the actions view only.

### Inspecting System Logs

For debugging a local deployment, `wskadmin syslog get` will show you the controller and invoker logs available. You can use `--grep` to grep the logs for specific patterns, or `--tid` to isolate logs specific to a specific transaction in the system. It is possible to isolate logs to a specific component (e.g., `controller0`). By default, logs are fetched from all available components.

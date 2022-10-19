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

# Tag-based Scheduling

Invoker machines may have different resources such as GPU, high CPU, etc.
For those who want to take advantage of such resources, the system should be able to schedule activations to a certain invoker with the resources.

## Tagging invokers

Operators can configure any tags for invokers.

```bash
invoker0 ansible_host=${INVOKER0} tags="['v1', 'gpu']"
invoker1 ansible_host=${INVOKER1} tags="['v1', 'cpu']"
invoker2 ansible_host=${INVOKER2} tags="['v2', 'gpu']"
invoker3 ansible_host=${INVOKER3} tags="['v2', 'cpu']"
invoker4 ansible_host=${INVOKER4} tags="['v1', 'mem']"
invoker5 ansible_host=${INVOKER5} tags="['v2', 'mem']"
invoker6 ansible_host=${INVOKER6} tags="['v2']"
invoker7 ansible_host=${INVOKER7} tags="['v2']"
invoker8 ansible_host=${INVOKER8}
invoker9 ansible_host=${INVOKER9}
```

Users can add the following annotations to their actions.

```
wsk action update params tests/dat/actions/params.js -i -a invoker-resources '["v2", "gpu"]'
```

Activation for this action will be delivered to invoker2.

The annotations and the corresponding target invokers are as follows.

* `["v1", "gpu"]` -> `invoker0`
* `["v2", "gpu"]` -> `invoker2`
* `["v1", "cpu"]` -> `invoker1`
* `["v2"]` -> One of `invoker2`, `invoker3`, `invoker5`, `invoker6`, and `invoker7`
* `["v1"]` -> One of `invoker0`, `invoker1`, `invoker4`
* `No annotation` -> One of `invoker8` and `invoker9` is chosen first. if they have no resource, choose one of the invokers with tags.

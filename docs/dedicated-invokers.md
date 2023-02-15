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

# Dedicated Invokers

Actions run on any invokers in OpenWhisk. But users may want to run their actions on a certain invoker(s) for some reason such as IP-based ACL.
This is to provide dedicated invokers for a namespace. Operators can configure a dedicated namespace for invokers and all activations from the namespace will be delivered to the dedicated invokers only.

## Tagging Invokers
Operators can configure any tags for invokers.

```bash
invoker0 ansible_host=${INVOKER01} tags="['dedicated']" dedicatedNamespaces="['namespace1']"
invoker1 ansible_host=${INVOKER02} tags="['dedicated']" dedicatedNamespaces="['namespace2']"
```

Users can add the following annotations to their actions.

```
wsk action update params tests/dat/actions/params.js -i -a invoker-resources '["dedicated"]'
```

So this feature is based on the [tag-based-scheduling](./tag-based-scheduling.md).
The `dedicatedNamespaces` field is used to make sure the invokers are not used by other than allowed namespaces and users can decide whether to run their actions on dedicated invokers using the `dedicated` tag.

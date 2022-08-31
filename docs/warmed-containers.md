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

# Warmed Containers

Warmed containers can improve their performance by skipping the container initialization step.
It may be beneficial to configure the number of warmed containers to keep, and the duration to keep them according to the characteristics of workloads.

## Configuration

The configurations are only effective with the FPC scheduler.
They can be configured using the limit configurations for each namespace.

```scala
case class UserLimits(invocationsPerMinute: Option[Int] = None,
                      concurrentInvocations: Option[Int] = None,
                      firesPerMinute: Option[Int] = None,
                      allowedKinds: Option[Set[String]] = None,
                      storeActivations: Option[Boolean] = None,
                      warmedContainerKeepingCount: Option[Int] = None,
                      warmedContainerKeepingTimeout: Option[String] = None)
```

So those can be configured in the same way that operators configure the `invocationPerMinute` limit.

```json
{
  "_id": "guest/limits",
  "invocationPerMinutes": 10,
  "warmedContainerKeepingCount": 8,
  "warmedContainerKeepingTimeout": "24 hours"
}
```

The namespace-specific configurations would override the default, system-wide configurations.
In the above example, the system will keep 8 warmed containers for 24 hours even if there is no activation at all.

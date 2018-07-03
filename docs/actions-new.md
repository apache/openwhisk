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

OpenWhisk supports [several languages and runtimes](actions.md#languages-and-runtimes) but
there may be other languages or runtimes that are important for your organization, for which
you want tighter integration with the platform. Adding a new language, or runtime with bespoke
packages and third party dependencies is the same from the OpenWhisk platform which is language
and runtime agnostic.

The canonical unit of execution is a container which implements a specific interface:
1. accepts an initialization payload (the code),
2. accepts runtime payload (the input parameters) and return a result,
3. prepares the activation context,
3. flushes all `stdout` and `stderr` logs and adds a frame marker at the end of the activation.

Any container which implements the interface may be used as an action.
It is in this way that you can add support for other languages or customized runtimes.

The interface is enforced via a [canonical test suite](../tests/src/test/scala/actionContainers/BasicActionRunnerTests.scala)
which validates the initialization protocol, the runtime protocol, ensures the activation context is correctly prepared,
and that the logs are properly framed. Your runtime should extend this test suite, and of course include additional tests
as needed.

The runtime support is best implemented in its own repository, which permits a management
lifecycle independent of the rest of the OpenWhisk platform which only requires the following
additions:
1. introduce the runtime specification into the [runtimes manifest](../ansible/files/runtimes.json),
2. add a new `actions-<your runtime>.md` file to the [docs](.) directory,
3. add a link to your new language or runtime to the [top level index](actions.md#languages-and-runtimes),
4. add an "echo" action to the [tests artifacts](../tests/dat/actions).

For some languages, it may also be necessary to
5. add the runtime to the [Swagger file](../core/controller/src/main/resources/apiv1swagger.json),
6. add the file extension of your runtime to the [test helpers](../tests/src/test/scala/common/rest/WskRest.scala).

**Note:** Steps 3-6 are ripe for automation and should further reduce the touch-points
for adding a new runtime to the OpenWhisk platform in the future.

### Canonical runtime repository

The runtime repository should follow the canonical structure used by other runtimes.

```
/path/to/runtime
├── build.gradle         # Gradle build file to integrate with rest of build and test framework
├── core
│   └── <runtime name and version>
│       ├── Dockerfile   # builds the runtime's Docker image
│       └── ...          # your runtimes files which implement the action proxy
└── tests
    └── src              # tests suits...
        └── ...          # ... which extend canonical interface plus additional runtime specific tests
```

The [Docker skeleton repository](https://github.com/apache/incubator-openwhisk-runtime-docker)
is an example starting point to fork and modify for your new runtime.

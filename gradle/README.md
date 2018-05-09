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

# Gradle

Gradle is used to build OpenWhisk. It does not need to be pre-installed as it installs itself using the [Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html). To use it without installing, simply invoke the `gradlew` command at the root of the repository. You can also install `gradle` via [`apt`](http://linuxg.net/how-to-install-gradle-2-1-on-ubuntu-14-10-ubuntu-14-04-ubuntu-12-04-and-derivatives/) on Ubuntu or [`brew`](http://www.brewformulas.org/Gradle) on Mac. In the following we use `gradle` and `gradlew` as synonymous.

## Usage

In general, project level properties are set via `-P{propertyName}={propertyValue}`. A task is called via `gradle {taskName}` and a subproject task is called via `gradle :path:to:subproject:{taskName}`. To run tasks in parallel, use the `--parallel` flag (**Note:** It's an incubating feature and might break stuff).

### Build

To build all Docker images use `gradle distDocker` at the top level project, to build a specific component use `gradle :core:controller:distDocker`.

Project level options that can be used on `distDocker`:

- `dockerImageName` (*required*): The name of the image to build (e.g. whisk/controller)
- `dockerHost` (*optional*): The docker host to run commands on, default behaviour is docker's own `DOCKER_HOST` environment variable
- `dockerRegistry` (*optional*): The registry to push to
- `dockerImageTag` (*optional*, default 'latest'): The tag for the image
- `dockerTimeout` (*optional*, default 240): Timeout for docker operations in seconds
- `dockerRetries` (*optional*, default 3): How many times to retry docker operations
- `dockerBinary` (*optional*, default `docker`): The binary to execute docker commands

### Test

To run tests one uses the `test` task. OpenWhisk consolidates tests into a single `tests` project. Hence the command to run all tests is `gradle :tests:test`.

It is possible to run specific tests using [Gradle testfilters](https://docs.gradle.org/current/userguide/java_plugin.html#test_filtering). For example `gradle :tests:test --tests "your.package.name.TestClass.evenMethodName"`. Wildcard `*` may be used anywhere.

## Build your own `build.gradle`
In Gradle, most of the tasks we use are default tasks provided by plugins in Gradle. The [`scala` Plugin](https://docs.gradle.org/current/userguide/scala_plugin.html) for example includes tasks, that are needed to build Scala projects. Moreover, Gradle is aware of *Applications*. The [`application` Plugin](https://docs.gradle.org/current/userguide/application_plugin.html) provides tasks that are required to distribute a self-contained application. When `application` and `scala` are used in conjunction, they hook into each other and provide the tasks needed to distribute a Scala application. `distTar` for example compiles the Scala code, creates a jar containing the compiled classes and resources and creates a Tarball including that jar and all of its dependencies (defined in the dependencies section of `build.gradle`). It also creates a start-script which correctly sets the classpath for all those dependencies and starts the app.

In OpenWhisk, we want to distribute our application via Docker images. Hence we wrote a "plugin" that creates the task `distDocker`. That task will build an image from the `Dockerfile` that is located next to the `build.gradle` it is called from, for example Controller's `Dockerfile` and `build.gradle` are both located at `core/controller`.

If you want to create a new `build.gradle` for your component, simply put the `Dockerfile` right next to it and include `docker.gradle` by using

```
ext.dockerImageName = 'openwwhisk/{IMAGENAME}'
apply from: 'path/to/docker.gradle'
```

If your component needs to be build before you can build the image, make `distDocker` depend on any task needed to run before it, for example:

```
distDocker.dependsOn ':common:scala:distDocker', 'distTar'
```

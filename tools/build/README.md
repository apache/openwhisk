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

# Build helper scripts

This directory contains the following utilities.
- `redo`: a wrapper around Ansible and Gradle commands, for which examples are given below,
- `citool`: allows for command line monitoring of Jenkins and Travis CI builds.

## How to use `redo`

The script is called `redo` because for most development, one will want to "redo" the compilation and deployment.

- usage information: `redo -h`
- initialize environment and `docker-machine` (for mac): `redo setup prereq`
- start CouchDB container and initialize DB with system and guest keys: `redo couchdb initdb`
- start ElasticSearch container to store activations: `redo elasticsearch`
- start MongoDB container to as database backend: `redo mongodb`
- build and deploy system: `redo deploy`
- run tests: `redo props tests`

To do a fresh build and deploy all with one line for a first time run `redo setup prereq couchdb initdb deploy tests` as each of these is executed sequentially.

Individual components such as the `controller` may be rebuilt and redeployed as well.

  * To only build: `redo controller -b`.
  * To only teardown: `redo controller -x`.
  * To redeploy only: `redo controller -d`.
  * To do all at once: `redo controller -bxd` which is the default.

Additional arguments may be passed to underlying shell commands for Gradle and Ansible using `-a`.
For example, the following is handy to run a subset of all tests from the command line.

  * `redo tests -a '--tests package.name.TestClass.evenMethodName'`

Some components are dynamically generated. This is supported by a generic component name
which specifies a regex. The `runtime:([\w]+)` is one such component, useful for rebuilding
action runtime images.

  * `redo --dir /path/to/openwhisk-runtime-nodejs runtime:nodejs6action`

## How to use `citool`

This script allows for monitoring of ongoing Jenkins and Travis builds.
The script assumes by default that the monitored job is a Travis CI build hosted here `https://api.travis-ci.org/`.
To change the Travis (or Jenkins) host URL, use `-u`.

- usage information: `citool -h`
- monitor a Travis CI build with job number `N`: `citool monitor N`
- monitor same job `N` until completion: `citool monitor -p N`
- save job output to a file: `citool -o monitor N`
- for Travis CI matrix builds, use the matrix index after the job number as in `citool monitor N.i` where 1 <= i <= matrix builds.

To monitor a Jenkins build `B` with job number `N` on host `https://jenkins.host:port`:
```
citool -u https://jenkins.host:port -b B monitor N
```

The script also allows for gathering controller and invoker log artifacts from a Jenkins build job. For example,
to retrieve logs for a deployment with 1 controller and 1 invoker for build `B` with job number `N` on
host `https://jenkins.host:port` with the artifacts are stored in `whisk/logs` relative to the job URL:

```
citool -u https://jenkins.host:port -b B cat whisk/logs N
```

It is sometimes convenient to save the logs locally (via `citool -o ...`) to avoid fetching them repeatedly if one wishes
to inspect the logs and extract a specific transaction. Logs statements may be sorted according to their timestamps using `cat -s`.
Additionally to grep for a specific expression, use `cat -g`.

```
citool -o -u https://jenkins.host:port -b B cat -s -g "tid_123" whisk/logs N
```

The logs are saved to `./B-build.log` and can be reprocessed using `citool` with `-i`.

```
citool -i -b B cat -s -g "tid_124" whisk/logs N
```

## Gradle Build Scan Integration

OpenWhisk builds on CI setups have [Gradle Build Scan](https://gradle.com/build-scans) integrated. Each build on Travis pushes scan reports to
[Gradle Scan Community Hosted Server](https://scans.gradle.com). To see the scan report you need to check the Travis build logs for lines like
below

```
Publishing build scan...
https://gradle.com/s/reldo4qqlg3ka
```

The url above is the scan report url and is unique per build

## Troubleshooting

If you encounter an error `ImportError: No module named pkg_resources` while running `redo`, try the workaround below
or see [these instructions](https://pypi.python.org/pypi/setuptools/0.9.8#installation-instructions) for upgrading `setuptools`.

```
pip install --upgrade setuptools
```

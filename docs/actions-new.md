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
## Adding Action Language Runtimes

OpenWhisk supports [several languages and runtimes](actions.md#languages-and-runtimes) but
there may be other languages or runtimes that are important for your organization, and for
which you want tighter integration with the platform. The OpenWhisk platform is extensible
and you can add new languages or runtimes (with custom packages and third-party dependencies)
following the guide described here.

### Runtime general requirements

The unit of execution for all functions is a [Docker container](https://docs.docker.com) which
must implement a specific [Action interface](#action-interface) that, in general performs:

1. **[Initialization](#initialization)** - accepts an initialization payload (the code) and prepared for execution,
2. **[Activation](#activation)** - accepts a runtime payload (the input parameters) and
   - prepares the activation context,
   - runs the function,
   - returns the function result,
3. **[Logging](#logging)** - flushes all `stdout` and `stderr` logs and adds a frame marker at the end of the activation.

The specifics of the [Action interface](#action-interface) and its functions are shown below.

### Platform requirements

In order for your language runtime to be properly recognized by the OpenWhisk platform,
and officially recognized by the Apache OpenWhisk project, please follow these
requirements and best practices:

1. Implement the runtime in its own repository to permit a management lifecycle independent of the rest of the OpenWhisk platform.
2. Introduce the runtime specification into the [runtimes manifest](../ansible/files/runtimes.json),
3. Add the runtime to the [Swagger file](../core/controller/src/main/resources/apiv1swagger.json)
4. Add a new `actions-<your runtime>.md` file to the [docs](.) directory,
5. Add a link to your new runtime doc to the [top level actions index](actions.md#languages-and-runtimes).
6. Add a standard [test action](#the-test-action) to the [tests artifacts directory](../tests/dat/actions/unicode.tests) (as shown below),

The new runtime repository should conform to the [Canonical runtime repository](#canonical-runtime-repository) layout (as shown below).
Further, you should automate and pass the following test suites:
- [Action Interface tests](#action-interface-tests)
- [Runtime proxy tests](#runtime-proxy-tests)

### The runtimes manifest

Actions when created specify the desired runtime for the function via a property called "kind".
When using the `wsk` CLI, this is specified as `--kind <runtime-kind>`. The value is typically
a string describing the language (e.g., `nodejs`) followed by a colon and the version for the runtime
as in `nodejs:14` or `php:7.4`.

The manifest is a map of runtime family names to an array of specific kinds. The details of the
schema are found in the [Exec Manifest](../common/scala/src/main/scala/org/apache/openwhisk/core/entity/ExecManifest.scala).
As an example, the following entry add a new runtime family called `nodejs` with a single kind
`nodejs:14`.

```json
{
  "nodejs": [{
    "kind": "nodejs:14",
    "default": true,
    "image": {
      "prefix": "openwhisk",
      "name": "action-nodejs-v10",
      "tag": "latest"
    }
  }]
}
```

The `default` property indicates if the corresponding kind should be treated as the
default for the runtime family. The JSON `image` structure defines the Docker image name
that is used for actions of this kind (e.g., `openwhisk/nodejs10action:latest` for the
JSON example above).

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

The [Docker skeleton repository](https://github.com/apache/openwhisk-runtime-docker)
is an example starting point to fork and modify for your new runtime.

### The test action

The standard test action is shown below in JavaScript. It should be adapted for the
new language and added to the [test artifacts directory](../tests/dat/actions/unicode.tests)
with the name `<runtime-kind>.txt` for plain text file or `<runtime-kind>.bin` for a
a binary file. The `<runtime-kind>` must match the value used for `kind` in the corresponding
runtime manifest entry, replacing `:` in the kind with a `-`.
For example, a plain text function for `nodejs:14` becomes `nodejs-14.txt`.

```js
function main(args) {
    var str = args.delimiter + " ☃ " + args.delimiter;
    console.log(str);
    return { "winter": str };
}
```

### Action Interface

An action consists of the user function (and its dependencies) along with a _proxy_ that implements a
canonical protocol to integrate with the OpenWhisk platform.

The proxy is a web server with two endpoints.
* It listens on port `8080`.
* It implements `/init` to initialize the container.
* It also implements `/run` to activate the function.

The proxy also prepares the
[execution context](actions.md#accessing-action-metadata-within-the-action-body),
and flushes the logs produced by the function to stdout and stderr.

#### Initialization

The initialization route is `/init`. It must accept a `POST` request with a JSON object as follows:
```
{
  "value": {
    "name" : String,
    "main" : String,
    "code" : String,
    "binary": Boolean,
    "env": Map[String, String]
  }
}
```

* `name` is the name of the action.
* `main` is the name of the function to execute.
* `code` is either plain text or a base64 encoded string for binary functions (i.e., a compiled executable).
* `binary` is false if `code` is in plain text, and true if `code` is base64 encoded.
* `env` is a map of key-value pairs of properties to export to the environment. And contains several properties starting with the `__OW_` prefix that are specific to the running action.
  * `__OW_API_KEY` the API key for the subject invoking the action, this key may be a restricted API key. This property is absent unless explicitly [requested](./annotations.md#annotations-for-all-actions).
  * `__OW_NAMESPACE` the namespace for the _activation_ (this may not be the same as the namespace for the action).
  * `__OW_ACTION_NAME` the fully qualified name of the running action.
  * `__OW_ACTION_VERSION` the internal version number of the running action.
  * `__OW_ACTIVATION_ID` the activation id for this running action instance.
  * `__OW_DEADLINE` the approximate time when this initializer will have consumed its entire duration quota (measured in epoch milliseconds).


The initialization route is called exactly once by the OpenWhisk platform, before executing a function.
The route should report an error if called more than once. It is possible however that a single initialization
will be followed by many activations (via `/run`). If an `env` property is provided, the corresponding environment
variables should be defined before the action code is initialized.

**Successful initialization:** The route should respond with `200 OK` if the initialization is successful and
the function is ready to execute. Any content provided in the response is ignored.

**Failures to initialize:** Any response other than `200 OK` is treated as an error to initialize. The response
from the handler if provided must be a JSON object with a single field called `error` describing the failure.
The value of the error field may be any valid JSON value. The proxy should make sure to generate meaningful log
message on failure to aid the end user in understanding the failure.

**Time limit:** Every action in OpenWhisk has a defined time limit (e.g., 60 seconds). The initialization
must complete within the allowed duration. Failure to complete initialization within the allowed time frame
will destroy the container.

**Limitation:** The proxy does not currently receive any of the activation context at initialization time.
There are scenarios where the context is convenient if present during initialization. This will require a
change in the OpenWhisk platform itself. Note that even if the context is available during initialization,
it must be reset with every new activation since the information will change with every execution.

#### Activation

The proxy is ready to execute a function once it has successfully completed initialization. The OpenWhisk
platform will invoke the function by posting an HTTP request to `/run` with a JSON object providing a new
activation context and the input parameters for the function. There may be many activations of the same
function against the same proxy (viz. container). Currently, the activations are guaranteed not to overlap
— that is, at any given time, there is at most one request to `/run` from the OpenWhisk platform.

The route must accept a JSON object and respond with a JSON object, otherwise the OpenWhisk platform will
treat the activation as a failure and proceed to destroy the container. The JSON object provided by the
platform follows the following schema:
```
{
  "value": JSON,
  "namespace": String,
  "action_name": String,
  "api_host": String,
  "api_key": String,
  "activation_id": String,
  "transaction_id": String,
  "deadline": Number
}
```

* `value` is a JSON object and contains all the parameters for the function activation.
* `namespace` is the OpenWhisk namespace for the action (e.g., `whisk.system`).
* `action_name` is the [fully qualified name](reference.md#fully-qualified-names) of the action.
* `activation_id` is a unique ID for this activation.
* `transaction_id` is a unique ID for the request of which this activation is part of.
* `deadline` is the deadline for the function.
* `api_key` is the API key used to invoke the action.

The `value` is the function parameters. The rest of the properties become part of the activation context
which is a set of environment variables constructed by capitalizing each of the property names, and prefixing
the result with `__OW_`. Additionally, the context must define `__OW_API_HOST` whose value
is the OpenWhisk API host. This value is currently provided as an environment variable defined at container
startup time and hence already available in the context.

**Successful activation:** The route must respond with `200 OK` if the activation is successful and
the function has produced a JSON object as its result. The response body is recorded as the [result
of the activation](actions.md#understanding-the-activation-record).

**Failed activation:** Any response other than `200 OK` is treated as an activation error. The response
from the handler must be a JSON object with a single field called `error` describing the failure.
The value of the error field may be any valid JSON value. Should the proxy fail to respond with a JSON
object, the OpenWhisk platform will treat the failure as an uncaught exception. These two failures modes are
distinguished by the value of the `response.status` in the [activation record](actions.md#understanding-the-activation-record)
which is "application error" if the proxy returned an "error" object, and "action developer error" otherwise.

**Time limit:** Every action in OpenWhisk has a defined time limit (e.g., 60 seconds). The activation
must complete within the allowed duration. Failure to complete activation within the allowed time frame
will destroy the container.

#### Logging

The proxy must flush all the logs produced during initialization and execution and add a frame marker
to denote the end of the log stream for an activation. This is done by emitting the token
[`XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX`](https://github.com/apache/openwhisk/blob/59abfccf91b58ee39f184030374203f1bf372f2d/core/invoker/src/main/scala/whisk/core/containerpool/docker/DockerContainer.scala#L51)
as the last log line for the `stdout` _and_ `stderr` streams. Failure to emit this marker will cause delayed
or truncated activation logs.

### Testing

#### Action Interface tests

The [Action interface](#action-interface) is enforced via a [canonical test suite](../tests/src/test/scala/actionContainers/BasicActionRunnerTests.scala) which validates the initialization protocol, the runtime protocol,
ensures the activation context is correctly prepared, and that the logs are properly framed. Your
runtime should extend this test suite, and of course include additional tests as needed.

#### Runtime proxy tests

There is a [canonical test harness](../tests/src/test/scala/actionContainers/BasicActionRunnerTests.scala)
for validating a new runtime.

The tests verify that the proxy can handle the following scenarios:
* Test the proxy can handle the identity functions (initialize and run).
* Test the proxy can handle pre-defined environment variables as well as initialization parameters.
* Test the proxy properly constructs the activation context.
* Test the proxy can properly handle functions with Unicode characters.
* Test the proxy can handle large payloads (more than 1MB).
* Test the proxy can handle an entry point other than "main".
* Test the proxy does not permit re-initialization.
* Test the error handling for an action returning an invalid response.
* Test the proxy when initialized with no content.

The canonical test suite should be extended by the new runtime tests. Additional
tests will be required depending on the feature set provided by the runtime.

Since the OpenWhisk platform is language and runtime agnostic, it is generally not
necessary to add integration tests. That is the unit tests verifying the protocol are
sufficient. However, it may be necessary in some cases to modify the `wsk` CLI or
other OpenWhisk clients. In which case, appropriate tests should be added as necessary.
The OpenWhisk platform will perform a generic integration test as part of its basic
system tests. This integration test will require a [test function](#the-test-action) to
be available so that the test harness can create, invoke, and delete the action.

### Supporting Additional Execution Environments

There are now several runtimes that support execution environments in addition to OpenWhisk. Currently only an interface for single entrypoint execution environments has been defined, but more could be defined in the future.

#### Action Proxy Single Entrypoint Interface

Single entrypoint proxies are proxies that have only onde addressable http endpoint. They do not use `/init` and `/run` enpoints utilized by standard OpenWhisk runtime environments; instead both the initialization and activation are handled through one endpoint. The first example of such a proxy was implemented for Knative Serving, but the same interface can be used for any single entrypoint execution environment. In an effort to standardize how the various action proxy implementation containers are able to handle single entrypoint execution environments (such as Knative Serving), there is a description of the contract and example cases outlining how a container should respond with a given input. The descriptions and example cases are documented in [Single Entrypoint Proxy Contract](single_entrypoint_proxy_contract.md).

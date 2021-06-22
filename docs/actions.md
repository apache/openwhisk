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

# OpenWhisk Actions

Actions are stateless functions that run on the OpenWhisk platform. For example, an action can
be used to detect the faces in an image, respond to a database change, respond to an API call,
or post a Tweet. In general, an action is invoked in response to an event and produces some
observable output.

An action may be created from a function programmed using a number of [supported languages and runtimes](#languages-and-runtimes),
or from a binary-compatible executable, or even executables packaged as Docker containers.

* The OpenWhisk CLI [`wsk`](https://github.com/apache/openwhisk-cli/releases)
makes it easy to create and invoke actions. Instructions for configuring the CLI are available [here](cli.md).
* You can also use the [REST API](rest_api.md).

While the actual function code will be specific to a [language and runtime](#languages-and-runtimes),
the OpenWhisk operations to create, invoke and manage an action are the same regardless of the
implementation choice. We recommend that you review [the basics](#the-basics) before moving on to
advanced topics.

* [The basics of working with actions](#the-basics) (_start here_)
* Common `wsk` CLI operations and tips
  * [Watching action output](#watching-action-output)
  * [Getting actions](#getting-actions)
  * [Listing actions](#listing-actions)
  * [Deleting actions](#deleting-actions)
* [Accessing action metadata within the action body](#accessing-action-metadata-within-the-action-body)
* [Securing your action](security.md)
* [Concurrency in actions](concurrency.md)

## Languages and Runtimes

Longer tutorials that are specific to a language of your choice are listed below.
We recommend reading the basics in this document first, which are language agnostic, before getting deeper
into a language-specific tutorial. If your preferred language isn't supported directly, you may find
the [Docker](actions-docker.md) action or [native binary](actions-docker.md#creating-native-actions)
paths more suitable. Or, you can [create a new runtime](actions-new.md).

* [Ballerina](actions-ballerina.md)
* [Go](actions-go.md)
* [Java](actions-java.md)
* [JavaScript](actions-nodejs.md)
* [PHP](actions-php.md)
* [Python](actions-python.md)
* [Ruby](actions-ruby.md)
* [Rust](actions-rust.md)
* [Swift](actions-swift.md)
* [.NET Core](actions-dotnet.md)
* [Docker and native binaries](actions-docker.md)

Multiple actions from different languages may be composed together to create a longer processing
pipeline called a [sequence](#creating-action-sequences). The polyglot nature of the composition is
powerful in that it affords you the ability to use the right language for the problem you're solving,
and separates the orchestration of the dataflow between functions from the choice of language.
A more advanced form of composition is described [here](conductors.md).

If your runtime is not listed there, you can create a new one for your specific language.

You can create a new runtime in two ways:

- Implementing the [runtime specification](actions-new.md)
- Using the [ActionLoop engine](actions-actionloop.md) that provides a simplified path for building a new runtime.

Follow the instructions in [Updating Action Language Runtimes](actions-update.md) for updating, removing or renaming
runtime kinds or language families.

### How prewarm containers are provisioned without a reactive configuration

Prewarmed containers are created when an invoker starts, they are created according to runtimes.json's stemCells, e.g.
```
{
    "kind": "nodejs:14",
    "default": true,
    "image": {
        "prefix": "openwhisk",
        "name": "action-nodejs-v14",
        "tag": "nightly"
    },
    "deprecated": false,
    "attached": {
        "attachmentName": "codefile",
        "attachmentType": "text/plain"
     },
     "stemCells": [
     {
        "initialCount": 2,
        "memory": "256 MB"
     }
     ]
}
```
In the above example, there is only one runtime configuration, which is `nodejs:14`.
It has a stem cell configuration and 2 containers with 256MB memory for `nodejs:14` will be provisioned when an invoker starts.
When an activation with the `nodejs:14` kind arrives, one of the prewarm containers can be used to alleviate a cold start.
A prewarm container that is assigned to an action is moved to the busy pool and the invoker creates one more prewarm container to replenish the prewarm pool.
In this way, when no reactive configuration is configured, an invoker always maintains the same number of prewarm containers.

### How prewarmed containers are provisioned with a reactive configuration

With a reactive configuration, the number of prewarm containers is dynamically controlled, e.g.
```
{
    "kind": "nodejs:14",
    "default": true,
    "image": {
        "prefix": "openwhisk",
        "name": "action-nodejs-v14",
        "tag": "nightly"
    },
    "deprecated": false,
    "attached": {
        "attachmentName": "codefile",
        "attachmentType": "text/plain"
     },
     "stemCells": [
     {
        "initialCount": 2,
         "memory": "256 MB",
         "reactive": {
             "minCount": 1,
             "maxCount": 4,
             "ttl": "2 minutes",
             "threshold": 2,
             "increment": 1
     }
     ]
}
```
In the above example, there is a reactive configuration for `nodejs:14` and there are 4 underlying configurations.
* `minCount`: the minimum number of prewarm containers. The number of prewarm containers can't be fewer than this value
* `maxCount`: the maximum number of prewarm containers. The number of prewarm containers cannot exceed this value
* `ttl`: the amount of time that prewarm containers can exist without any activation. If no activation for the prewarm container arrives in the given time, the prewarm container will be removed
* `threshold` and `increment`: these two configurations control the number of new prewarm containers to be created.

The number of prewarmed containers is dynamically controlled when:
* they are expired due to a TTL, some prewarmed containers are removed to save resources.
* cold starts happen, some prewarm containers are created according to the following calculus.
  - `# of prewarm containers to be created` = `# of cold starts` / `threshold` * `increment`
  - ex1) `cold start number(2)` / `threshold(2)` * `increment(1)` = 1
  - ex2) `cold start number(4)` / `threshold(2)` * `increment(1)` = 2
  - ex3) `cold start number(8)` / `threshold(2)` * `increment(1)` = 4
  - ex4) `cold start number(16)` / `threshold(2)` * `increment(1)` = 4 (cannot exceed the maximum number)
* no activation arrives for long time, the number of prewarm containers will eventually converge to `minCount`.

## The basics

To use a function as an action, it must conform to the following:
- The function accepts a dictionary as input and produces a dictionary as output. The input and output dictionaries are
key-value pairs, where the key is a string and the value is any valid JSON value. The dictionaries are
canonically represented as JSON objects when interfacing to an action via the REST API or the `wsk` CLI.
- The function must be called `main` or otherwise must be explicitly exported to identify it as the entry point.
The mechanics may vary depending on your choice of language, but in general the entry point can be specified using the
`--main` flag when using the `wsk` CLI.

In this section, you'll invoke a built-in action using the `wsk` CLI, which you should
[download and configure](cli.md) first if necessary.

### Invoking a built-in action

Actions are identified by [fully qualified names](reference.md#fully-qualified-names) which generally have
three parts separated by a forward slash:
1. a namespace
2. a package name
3. the action name

As an example, we will work with a built-in sample action called `/whisk.system/samples/greeting`.
The namespace for this action is `whisk.system`, the package name
is `samples`, and the action name is `greeting`. There are other sample actions and
utility actions, and later you'll learn how to explore the platform to discover more actions.
You can learn more about [packages](packages.md) after completing the basic tutorial.

Let's take a look at the action body by saving the function locally:
```
wsk action get /whisk.system/samples/greeting --save
ok: saved action code to /path/to/openwhisk/greeting.js
```

This is a JavaScript function, which is indicated by the `.js` extension.
It will run using a [Node.js](http://nodejs.org/) runtime.
See [supported languages and runtimes](#languages-and-runtimes) for other languages and runtimes.

The contents of the file `greeting.js` should match the function below. It is a short function which
accepts optional parameters and returns a standard greeting.

```js
/**
 * @params is a JSON object with optional fields "name" and "place".
 * @return a JSON object containing the message in a field called "msg".
 */
function main(params) {
  // log the parameters to stdout
  console.log('params:', params);

  // if a value for name is provided, use it else use a default
  var name = params.name || 'stranger';

  // if a value for place is provided, use it else use a default
  var place = params.place || 'somewhere';

  // construct the message using the values for name and place
  return {msg:  'Hello, ' + name + ' from ' + place + '!'};
}
```

The command to invoke an action and get its result is `wsk action invoke <name> --result` as in:
```
wsk action invoke /whisk.system/samples/greeting --result
```

This command will print the following result to the terminal:
```json
{
  "msg": "Hello, stranger from somewhere!"
}
```

### Passing parameters to actions

Actions may receive parameters as input, and the `wsk` CLI makes it convenient to pass parameters to the actions
from the command line. Briefly, this is done with the flag `--param key value` where `key` is the property name and `value` is
any valid JSON value. There is a longer [tutorial on working with parameters](parameters.md) that you should read after completing
this basic walk-through.

The `/whisk.system/samples/greeting` action accepts two optional input arguments, which are used to tailor the
response. The default greeting as described earlier is "Hello, stranger from somewhere!". The words "stranger" and
"somewhere" may be replaced by specifying the following parameters respectively:
- `name` whose value will replace the word "stranger",
- `place` whose value will replace the word "somewhere".

```
wsk action invoke /whisk.system/samples/greeting --result --param name Dorothy --param place Kansas
{
  "msg": "Hello, Dorothy from Kansas!"
}
```

### Request-Response vs Fire-and-Forget

The style of invocation shown above is synchronous in that the request from the CLI _blocks_ until the
activation completes and the result is available from the OpenWhisk platform. This is generally useful
for rapid iteration and development.

You can invoke an action asynchronously as well, by dropping the `--result` command line option. In this case
the action is invoked, and the OpenWhisk platform returns an activation ID which you can use later to retrieve
the activation record.

 ```
wsk action invoke /whisk.system/samples/greeting
ok: invoked /whisk.system/samples/greeting with id 5a64676ec8aa46b5a4676ec8aaf6b5d2
 ```

To retrieve the activation record, you use the `wsk activation get <id>` command, as in:
```
wsk activation get 5a64676ec8aa46b5a4676ec8aaf6b5d2
ok: got activation 5a64676ec8aa46b5a4676ec8aaf6b5d2
{
  "activationId": "5a64676ec8aa46b5a4676ec8aaf6b5d2",
  "duration": 3,
  "response": {
    "result": {
      "msg": "Hello, stranger from somewhere!"
    },
    "status": "success",
    "success": true
  }, ...
}
```

Sometimes it is helpful to invoke an action in a blocking style and receiving the activation record entirely
instead of just the result. This is achieved using the `--blocking` command line parameter.

```
wsk action invoke /whisk.system/samples/greeting --blocking
ok: invoked /whisk.system/samples/greeting with id 5975c24de0114ef2b5c24de0118ef27e
{
  "activationId": "5975c24de0114ef2b5c24de0118ef27e",
  "duration": 3,
  "response": {
    "result": {
      "msg": "Hello, stranger from somewhere!"
    },
    "status": "success",
    "success": true
  }, ...
}
```

### Blocking invocations and timeouts

A blocking invocation request will _wait_ for the activation result to be available. The wait period
is the lesser of 60 seconds (this is the default for blocking invocations) or the action's configured
[time limit](reference.md#per-action-timeout-ms-default-60s).

The result of the activation is returned if it is available within the blocking wait period.
Otherwise, the activation continues processing in the system and an activation ID is returned
so that one may check for the result later, as with non-blocking requests
(see [here](#watching-action-output) for tips on monitoring activations).
When an action exceeds its configured time limit, the activation record will indicate this error.
See [understanding the activation record](#understanding-the-activation-record) for more details.

### Working with activations

Some common CLI commands for working with activations are:
- `wsk activation list`: lists all activations
- `wsk activation get --last`: retrieves the most recent activation record
- `wsk activation result <activationId>`: retrieves only the result of the activation (or use `--last` to get the most recent result).
- `wsk activation logs <activationId>`: retrieves only the logs of the activation.
- `wsk activation logs <activationId> --strip`: strips metadata from each log line so the logs are easier to read.

#### The `wsk activation list` command

The `activation list` command lists all activations, or activations filtered by namespace or name. The result set can be limited by using several flags:

```
Flags:
  -f, --full          include full activation description
  -l, --limit LIMIT   only return LIMIT number of activations from the collection with a maximum LIMIT of 200 activations (default 30)
      --since SINCE   return activations with timestamps later than SINCE; measured in milliseconds since Th, 01, Jan 1970
  -s, --skip SKIP     exclude the first SKIP number of activations from the result
      --upto UPTO     return activations with timestamps earlier than UPTO; measured in milliseconds since Th, 01, Jan 1970
```

For example, to list the last 6 activations:
```
wsk activation list --limit 6
```
<pre>
Datetime            Activation ID                    Kind      Start Duration   Status  Entity
2019-03-16 20:03:00 8690bc9904794c9390bc9904794c930e nodejs:6  warm  2ms        success guest/tripleAndIncrement:0.0.1
2019-03-16 20:02:59 7e76452bec32401db6452bec32001d68 nodejs:6  cold  32ms       success guest/increment:0.0.1
2019-03-16 20:02:59 097250ad10a24e1eb250ad10a23e1e96 nodejs:6  warm  2ms        success guest/tripleAndIncrement:0.0.1
2019-03-16 20:02:58 4991a50ed9ed4dc091a50ed9edddc0bb nodejs:6  cold  33ms       success guest/triple:0.0.1
2019-03-16 20:02:57 aee63124f3504aefa63124f3506aef8b nodejs:6  cold  34ms       success guest/tripleAndIncrement:0.0.1
2019-03-16 20:02:57 22da217c8e3a4b799a217c8e3a0b79c4 sequence  warm  3.46s      success guest/tripleAndIncrement:0.0.1
</pre>

The meaning of the different columns in the list are:

| Column | Description |
| :--- | :--- |
| `Datetime` | The date and time when the invocation occurred. |
| `Activation ID` | An activation ID that can be used to retrive the result using the `wsk activation get`, `wsk activation result` and `wsk activation logs` commands. |
| `Kind` | The runtime or action type |
| `Start` | An indication of the latency, i.e. if the runtime container was cold or warm started. |
| `Duration` | Time taken to execute the invocation. |
| `Status` | The outcome of the invocation. For an explanation of the various statuses, see the description of the `statusCode` below. |
| `Entity` | The fully qualified name of entity that was invoked. |

#### Understanding the activation record

Each action invocation results in an activation record which contains the following fields:

- `activationId`: The activation ID.
- `namespace` and `name`: The namespace and name of the entity.
- `start` and `end`: Timestamps recording the start and end of the activation. The values are in [UNIX time format](http://pubs.opengroup.org/onlinepubs/9699919799/basedefs/V1_chap04.html#tag_04_15).
- `logs`: An array of strings with the logs that are produced by the action during its activation. Each array element corresponds to a line output to `stdout` or `stderr` by the action, and includes the time and stream of the log output. The structure is as follows: `TIMESTAMP` `STREAM:` `LOG LINE`.
- `annotations`: An array of key-value pairs that record [metadata](annotations.md#annotations-specific-to-activations) about the action activation.
- `response`: A dictionary that defines the following keys
  - `status`: The activation result, which might be one of the following values:
    - *"success"*: the action invocation completed successfully.
    - *"application error"*: the action was invoked, but returned an error value on purpose, for instance because a precondition on the arguments was not met.
    - *"action developer error"*: the action was invoked, but it completed abnormally, for instance the action did not detect an exception, or a syntax error existed. This status code is also returned under specific conditions such as:
      - the action failed to initialize for any reason
      - the action exceeded its time limit during the init or run phase
      - the action specified a wrong docker container name
      - the action did not properly implement the expected [runtime protocol](actions-new.md)
    - *"whisk internal error"*: the system was unable to invoke the action.
  - `statusCode`: A value between 0 and 3 that maps to the activation result, as described by the *status* field:

    | statusCode | status                 |
    |:---------- |:---------------------- |
    | 0          | success                |
    | 1          | application error      |
    | 2          | action developer error |
    | 3          | whisk internal error   |
  - `success`: Is *true* if and only if the status is *"success"*.
  - `result`: A dictionary as a JSON object which contains the activation result. If the activation was successful, this contains the value that is returned by the action. If the activation was unsuccessful, `result` contains the `error` key, generally with an explanation of the failure.

### Creating and updating your own action

Earlier we saved the code from the `greeting` action locally. We can use it to create our own version of the action
in our own namespace.
```
wsk action create greeting greeting.js
ok: created action greeting
```

For convenience, you can omit the namespace when working with actions that belong to you. Also if there
is no package, then you simply use the action name without a [package](packages.md) name.
If you modify the code and want to update the action, you can use `wsk action update` instead of
`wsk action create`. The two commands are otherwise the same in terms of their command like parameters.

```
wsk action update greeting greeting.js
ok: updated action greeting
```

### Binding parameters to actions

Sometimes it is necessary or just convenient to provide values for function parameters. These can serve as
defaults, or as a way of reusing an action but with different parameters. Parameters can be bound to an action
and unless overridden later by an invocation, they will provide the specified value to the function.

Here is an example.

```
wsk action invoke greeting --result
{
  "msg": "Hello, stranger from somewhere!"
}
```
```
wsk action update greeting --param name Toto
ok: updated action greeting
```
```
wsk action invoke greeting --result
{
  "msg": "Hello, Toto from somewhere!"
}
```

You may still provide additional parameters, as in the `place`:
```
wsk action invoke greeting --result --param place Kansas
{
  "msg": "Hello, Toto from Kansas!"
}
```
and even override the `name`:
```
wsk action invoke greeting --result --param place Kansas --param name Dorothy
{
  "msg": "Hello, Dorothy from Kansas!"
}
```

### Action execution

When an invocation request is received, the system records the request and dispatches an activation.

The system returns an activation ID (in the case of a non-blocking invocation) to confirm that the invocation was received.
Notice that if there's a network failure or other failure which intervenes before you receive an HTTP response, it is possible
that OpenWhisk received and processed the request.

The system attempts to invoke the action once and records the `status` in the [activation record](#understanding-the-activation-record).
Every invocation that is successfully received, and that the user might be billed for, will eventually have an activation record.

Note that in the case of [*action developer error*](#understanding-the-activation-record), the action may
have partially run and generated externally visible side effects. It is the user's responsibility to check
whether such side effects actually happened, and issue retry logic if desired.
Also note that certain [*whisk internal errors*](#understanding-the-activation-record) will indicate that
an action started running but the system failed before the action registered completion.

### Further considerations
- Functions should be stateless, or *idempotent*. While the system does not enforce this property,
there is no guarantee that any state maintained by an action will be available across invocations. In some cases,
deliberately leaking state across invocations may be advantageous for performance, but also exposes some risks.
- An action executes in a sandboxed environment, namely a container. At any given time, a single activation will
execute inside the container. Subsequent invocations of the same action may reuse a previous container,
and there may exist more than one container at any given time, each having its own state.
- Invocations of an action are not ordered. If the user invokes an action twice from the command line or the REST API,
the second invocation might run before the first. If the actions have side effects, they might be observed in any order.
- There is no guarantee that actions will execute atomically. Two actions can run concurrently and their side effects
can be interleaved. OpenWhisk does not ensure any particular concurrent consistency model for side effects.
Any concurrency side effects will be implementation-dependent.
- Actions have two phases: an initialization phase, and a run phase. During initialization, the function is loaded
and prepared for execution. The run phase receives the action parameters provided at invocation time. Initialization
is skipped if an action is dispatched to a previously initialized container --- this is referred to as a _warm start_.
You can tell if an [invocation was a warm activation or a cold one requiring initialization](annotations.md#annotations-specific-to-activations)
by inspecting the activation record.
- An action runs for a bounded amount of time. This limit can be configured per action, and applies to both the
initialization and the execution separately. If the action time limit is exceeded during the initialization or run phase, the activation's response status is _action developer error_.
- Functions should follow best practices to reduce [vulnerabilities](security.md) by treating input as untrusted,
and be aware of vulnerabilities they may inherit from third-party dependencies.

## Creating action sequences

A powerful feature of the OpenWhisk programming model is the ability to compose actions together. A common
composition is a sequence of actions, where the result of one action becomes the input to the next action in
the sequence.

Here we will use several utility actions that are provided in the `/whisk.system/utils`
[package](packages.md) to create your first sequence.

1. Display the actions in the `/whisk.system/utils` package.

  ```
  wsk package get --summary /whisk.system/utils
  ```
  ```
  package /whisk.system/utils: Building blocks that format and assemble data
     (parameters: none defined)
   action /whisk.system/utils/split: Split a string into an array
     (parameters: payload, separator)
   action /whisk.system/utils/sort: Sorts an array
     (parameters: lines)
   ...
  ```

  You will be using the `split` and `sort` actions in this example shown here, although the package contains more actions.

2. Create an action sequence so that the result of one action is passed as an argument to the next action.

  ```
  wsk action create mySequence --sequence /whisk.system/utils/split,/whisk.system/utils/sort
  ```

  This action sequence converts some lines of text to an array, and sorts the lines.

3. Invoke the action:

  ```
  wsk action invoke --result mySequence --param payload "Over-ripe sushi,\nThe Master\nIs full of regret."
  ```
  ```json
  {
      "length": 3,
      "lines": [
          "Is full of regret.",
          "Over-ripe sushi,",
          "The Master"
      ]
  }
  ```

  In the result, you see that the lines are sorted.

**Note**: Parameters passed between actions in the sequence are explicit, except for default parameters.
Therefore parameters that are passed to the sequence action (e.g., `mySequence`) are only available to the first action in the sequence.
The result of the first action in the sequence becomes the input JSON object to the second action in the sequence (and so on).
This object does not include any of the parameters originally passed to the sequence unless the first action explicitly includes them in its result.
Input parameters to an action are merged with the action's default parameters, with the former taking precedence and overriding any matching default parameters.
For more information about invoking action sequences with multiple named parameters, learn about [setting default parameters](parameters.md#setting-default-parameters).

A more advanced form of composition using *conductor* actions is described [here](conductors.md).

## Watching action output

OpenWhisk actions might be invoked by other users, in response to various events, or as part of an action sequence. In such cases it can be useful to monitor the invocations.

You can use the OpenWhisk CLI to watch the output of actions as they are invoked.

1. Issue the following command from a shell:
```
wsk activation poll
```

This command starts a polling loop that continuously checks for logs from activations.

2. Switch to another window and invoke an action:

```
wsk action invoke /whisk.system/samples/helloWorld --param payload Bob
ok: invoked /whisk.system/samples/helloWorld with id 7331f9b9e2044d85afd219b12c0f1491
```

3. Observe the activation log in the polling window:

```
Activation: helloWorld (7331f9b9e2044d85afd219b12c0f1491)
  2016-02-11T16:46:56.842065025Z stdout: hello bob!
```

Similarly, whenever you run the poll utility, you see in real time the logs for any actions running on your behalf in OpenWhisk.

## Getting actions

Metadata that describes existing actions can be retrieved via the `wsk action get` command.

```
wsk action get hello
ok: got action hello
{
    "namespace": "guest",
    "name": "hello",
    "version": "0.0.1",
    "exec": {
        "kind": "nodejs:6",
        "binary": false
    },
    "annotations": [
        {
            "key": "exec",
            "value": "nodejs:6"
        }
    ],
    "limits": {
        "timeout": 60000,
        "memory": 256,
        "logs": 10
    },
    "publish": false
}
```

### Getting the URL for an action

An action can be invoked through the REST interface via an HTTPS request.
To get an action URL, execute the following command:

```
wsk action get greeting --url
```

A URL with the following format will be returned for standard actions:
```
ok: got action actionName
https://${APIHOST}/api/v1/namespaces/${NAMESPACE}/actions/greeting
```

Authentication is required when invoking an action via an HTTPS request using this resource path.
For more information regarding action invocations using the REST interface, see [Using REST APIs with OpenWhisk](rest_api.md#actions).

Another way of invoking an action which does not require authentication is via
[web actions](webactions.md#web-actions).

Any action may be exposed as a web action, using the `--web true` command line option at action
creation time (or later when updating the action).

```
wsk action update greeting --web true
ok: updated action greeting
```

The resource URL for a web action is different:
```
wsk action get greeting --url
ok: got action greeting
https://${APIHOST}/api/v1/web/${NAMESPACE}/${PACKAGE}/greeting
```

You can use `curl` or wget to invoke the action.
```
curl `wsk action get greeting --url | tail -1`.json
{
  "payload": "Hello, Toto from somewhere!"
}
```

### Saving action code

Code associated with an existing action may be retrieved and saved locally. Saving can be performed on all actions except sequences and docker actions.

1. Save action code to a filename that corresponds with an existing action name in the current working directory. A file extension that corresponds to the action kind is used, or an extension of `.zip` will be used for action code that is a zip file.
  ```
  wsk action get /whisk.system/samples/greeting --save
  ok: saved action code to /path/to/openwhisk/greeting.js
  ```

2. You may provide your own file name and extension as well using the `--save-as` flag.
  ```
  wsk action get /whisk.system/samples/greeting --save-as hello.js
  ok: saved action code to /path/to/openwhisk/hello.js
  ```

## Listing actions

You can list all the actions that you have created using `wsk action list`:

```
wsk action list
```
```
actions
/guest/mySequence                  private sequence
/guest/greeting                    private nodejs:6
```

Here, we see actions listed in order from most to least recently updated. For easier browsing, you can use the flag `--name-sort` or `-n` to sort the list alphabetically:

```
wsk action list --name-sort
```
```
actions
/guest/mySequence                  private sequence
/guest/greeting                    private nodejs:6
```

Notice that the list is now sorted alphabetically by namespace, then package name if any, and finally action name, with the default package (no specified package) listed at the top.

**Note**: The printed list is sorted alphabetically after it is received from the platform. Other list flags such as `--limit` and `--skip` will be applied to the block of actions before they are received for sorting. To list actions in order by creation time, use the flag `--time`.

As you write more actions, this list gets longer and it can be helpful to group related actions into [packages](packages.md). To filter your list of actions to just those within a specific package, you can use:

```
wsk action list /whisk.system/utils
```
```
actions
/whisk.system/utils/hosturl        private nodejs:6
/whisk.system/utils/namespace      private nodejs:6
/whisk.system/utils/cat            private nodejs:6
/whisk.system/utils/smash          private nodejs:6
/whisk.system/utils/echo           private nodejs:6
/whisk.system/utils/split          private nodejs:6
/whisk.system/utils/date           private nodejs:6
/whisk.system/utils/head           private nodejs:6
/whisk.system/utils/sort           private nodejs:6
```

## Deleting actions

You can clean up by deleting actions that you do not want to use.

1. Run the following command to delete an action:
  ```
  wsk action delete greeting
  ok: deleted greeting
  ```

2. Verify that the action no longer appears in the list of actions.
  ```
  wsk action list
  ```
  ```
  actions
  /guest/mySequence                private sequence
  ```

## Accessing action metadata within the action body

The action environment contains several properties that are specific to the running action.
These allow the action to programmatically work with OpenWhisk assets via the REST API,
or set an internal alarm when the action is about to use up its allotted time budget.
The properties are accessible via the system environment for all supported runtimes:
Node.js, Python, Swift, Java and Docker actions when using the OpenWhisk Docker skeleton.

* `__OW_API_HOST` the API host for the OpenWhisk deployment running this action.
* `__OW_API_KEY` the API key for the subject invoking the action, this key may be a restricted API key. This property is absent unless explicitly [requested](./annotations.md#annotations-for-all-actions).
* `__OW_NAMESPACE` the namespace for the _activation_ (this may not be the same as the namespace for the action).
* `__OW_ACTION_NAME` the fully qualified name of the running action.
* `__OW_ACTION_VERSION` the internal version number of the running action.
* `__OW_ACTIVATION_ID` the activation id for this running action instance.
* `__OW_DEADLINE` the approximate time when this action will have consumed its entire duration quota (measured in epoch milliseconds).

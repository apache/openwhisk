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
# Annotations on OpenWhisk assets

OpenWhisk actions, triggers, rules and packages (collectively referred to as assets) may be decorated with `annotations`. Annotations are attached to assets just like parameters with a `key` that defines a name and `value` that defines the value. It is convenient to set them from the command line interface (CLI) via `--annotation` or `-a` for short.

Rationale: Annotations were added to OpenWhisk to allow for experimentation without making changes to the underlying asset schema. We had, until the writing of this document, deliberately not defined what `annotations` are permitted. However as we start to use annotations more heavily to impart semantic changes, it's important that we finally start to document them.

The most prevalent use of annotations to date is to document actions and packages. You'll see many of the packages in the OpenWhisk catalog carry annotations such as a description of the functionality offered by their actions, which parameters are required at package binding time, and which are invoke-time parameters, whether a parameter is a "secret" (e.g., password), or not. We have invented these as needed, for example to allow for UI integration.

Here is a sample set of annotations for an `echo` action which returns its input arguments unmodified (e.g., `function main(args) { return args }`). This action may be useful for logging input parameters for example as part of a sequence or rule.

```
wsk action create echo echo.js \
    -a description 'An action which returns its input. Useful for logging input to enable debug/replay.' \
    -a parameters  '[{ "required":false, "description": "Any JSON entity" }]' \
    -a sampleInput  '{ "msg": "Five fuzzy felines"}' \
    -a sampleOutput '{ "msg": "Five fuzzy felines"}'
```

The annotations we have used for describing packages are:

* `description`: a pithy description of the package
* `parameters`: an array describing parameters that are scoped to the package (described further below)

Similarly, for actions:

* `description`: a pithy description of the action
* `parameters`: an array describing actions that are required to execute the action
* `sampleInput`: an example showing the input schema with typical values
* `sampleOutput`: an example showing the output schema, usually for the `sampleInput`

The annotations we have used for describing parameters include:

* `name`: the name of the parameter
* `description`: a pithy description of the parameter
* `doclink`: a link to further documentation for parameter (useful for OAuth tokens for example)
* `required`: true for required parameters and false for optional ones
* `bindTime`: true if the parameter should be specified when a package is bound
* `type`: the type of the parameter, one of `password`, `array` (but may be used more broadly)

The annotations are _not_ checked. So while it is conceivable to use the annotations to infer if a composition of two actions into a sequence is legal, for example, the system does not yet do that.

# Annotations for all actions

The following annotations on an action are available.

* `provide-api-key`: This annotation may be attached to actions which require an API key, for example to make REST API calls to the OpenWhisk host. For newly created actions, if not specified, it defaults to a false value. For existing actions, the absence of this annotation, or its presence with a value that is not _falsy_ (i.e., a value that is different from zero, null, false, and the empty string) will cause an API key to be present in the [action execution context](./actions.md#accessing-action-metadata-within-the-action-body).

# Annotations specific to web actions

Web actions are enabled with explicit annotations which decorate individual actions. The annotations only apply to the [web actions](webactions.md) API,
and must be present and explicitly set to `true` to have an affect. The annotations have no meaning otherwise in the system. The annotations are:

* `web-export`: Makes its corresponding action accessible to REST calls _without_ authentication. We call these [_web actions_](webactions.md) because they allow one to use OpenWhisk actions from a browser for example. It is important to note that the _owner_ of the web action incurs the cost of running them in the system (i.e., the _owner_ of the action also owns the activations record). The rest of the annotations described below have no effect on the action unless this annotation is also set.
* `final`: Makes all of the action parameters that are already defined immutable. A parameter of an action carrying the annotation may not be overridden by invoke-time parameters once the parameter has a value defined through its enclosing package or the action definition.
* `raw-http`: When set, the HTTP request query and body parameters are passed to the action as reserved properties.
* `web-custom-options`: When set, this annotation enables a web action to respond to OPTIONS requests with customized headers, otherwise a [default CORS response](webactions.md#options-requests) applies.
* `require-whisk-auth`: This annotation protects the web action so that it is only invoked by requests that provide appropriate authentication credentials. When set to a boolean value, it controls whether or not the request's Basic Authentication value (i.e. Whisk auth key) will be authenticated - a value of `true` will authenticate the credentials, a value of `false` will invoke the action without any authentication. When set to a number or a string, this value must match the request's `X-Require-Whisk-Auth` header value. In both cases, it is important to note that the _owner_ of the web action will still incur the cost of running them in the system (i.e., the _owner_ of the action also owns the activations record).

# Annotations specific to activations

The system decorates activation records with annotations as well. They are:

* `path`: the fully qualified path name of the action that generated the activation. Note that if this activation was the result of an action in a package binding, the path refers to the parent package.
* `binding`: the entity path of the package binding. Note that this is only present for actions in a package binding.
* `kind`: the kind of action executed, and one of the support OpenWhisk runtime kinds.
* `limits`: the time, memory and log limits that this activation were subject to.

Additionally for sequence related activations, the system will generate the following annotations:

* `topmost`: this is only present for an outermost sequence action.
* `causedBy`: this is only present for actions that are contained in a sequence.

Lastly, and in order to provide you with some performance transparency, activations also record:

* `waitTime`: the time spent waiting in the internal OpenWhisk system. This is roughly the time spent between the controller receiving the activation request and when the invoker provisioned a container for the action.
* `initTime`: the time spent initializing the function. If this value is present, the action required initialization and represents a cold start. A warm activation will skip initialization, and in this case, the annotation is not generated.

An example of these annotations as they would appear in an activation record is shown below.

```javascript
"annotations": [
  {
    "key": "path",
    "value": "guest/echo"
  },
  {
    "key": "waitTime",
    "value": 66
  },
  {
    "key": "kind",
    "value": "nodejs:6"
  },
  {
    "key": "initTime",
    "value": 50
  },
  {
    "key": "limits",
    "value": {
      "logs": 10,
      "memory": 256,
      "timeout": 60000
    }
  }
]
```

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
# Conductor Actions

Conductor actions make it possible to build and invoke a series of actions, similar to sequences. However, whereas the components of a sequence action must be specified before invoking the sequence, conductor actions can decide the series of actions to invoke at run time.

In this document, we specify conductor actions and illustrate them with a simple example: a _tripleAndIncrement_ action.

Suppose we define a _triple_ action in a source file `triple.js`:

```javascript
function main({ value }) { return { value: value * 3 } }
```

We create the action _triple_:

```
wsk action create triple triple.js
```

We define an _increment_ action in a source file `increment.js`:

```javascript
function main({ value }) { return { value: value + 1 } }
```

We create the action _increment_:

```
wsk action create increment increment.js
```

## Conductor annotation

We define the _tripleAndIncrement_ action in a source file `tripleAndIncrement.js`:

```javascript
function main(params) {
    let step = params.$step || 0
    delete params.$step
    switch (step) {
        case 0: return { action: 'triple', params, state: { $step: 1 } }
        case 1: return { action: 'increment', params, state: { $step: 2 } }
        case 2: return { params }
    }
}
```

We create a _conductor_ action by specifying the _conductor_ annotation:

```
wsk action create tripleAndIncrement tripleAndIncrement.js -a conductor true
```

A _conductor action_ is an action with a _conductor_ annotation with a value that is not _falsy_, i.e., a value that is different from zero, null, false, and the empty string.

At this time, the conductor annotation is ignored on sequence actions.

Because a conductor action is an action, it has all the attributes of an action (name, namespace, default parameters, limits...) and it can be managed as such, for instance using the `wsk action` CLI commands. It can be part of a package or be a web action.

In essence, the _tripleAndIncrement_ action builds a sequence of two actions by encoding a program with three steps:

- step 0: invoke the _triple_ action on the input dictionary,
- step 1: invoke the _increment_ action on the output dictionary from step 1,
- step 2: return the output dictionary from step 2.

At each step, the conductor action specifies how to continue or terminate the execution by means of a _continuation_. We explain continuations after discussing invocation and activations.

## Invocation

A conductor action is invoked like a regular [action](actions.md), for instance:

```
wsk action invoke tripleAndIncrement -r -p value 3
```
```json
{
    "value": 10
}
```

Blocking and non-blocking invocations are supported. As usual, a blocking invocation may timeout before the completion of the invocation.

## Activations

One invocation of the conductor action results in multiple activations, for instance:

```
wsk action invoke tripleAndIncrement -p value 3
```
```
ok: invoked /_/tripleAndIncrement with id 4f91f9ed0d874aaa91f9ed0d87baaa07
```
```
wsk activation list
```
<pre>
Datetime            Activation ID                    Kind      Start Duration   Status   Entity
2019-03-16 20:03:00 8690bc9904794c9390bc9904794c930e nodejs:6  warm  2ms        success  guest/tripleAndIncrement:0.0.1
2019-03-16 20:02:59 7e76452bec32401db6452bec32001d68 nodejs:6  cold  32ms       success  guest/increment:0.0.1
2019-03-16 20:02:59 097250ad10a24e1eb250ad10a23e1e96 nodejs:6  warm  2ms        success  guest/tripleAndIncrement:0.0.1
2019-03-16 20:02:58 4991a50ed9ed4dc091a50ed9edddc0bb nodejs:6  cold  33ms       success  guest/triple:0.0.1
2019-03-16 20:02:57 aee63124f3504aefa63124f3506aef8b nodejs:6  cold  34ms       success  guest/tripleAndIncrement:0.0.1
2019-03-16 20:02:57 22da217c8e3a4b799a217c8e3a0b79c4 sequence  warm  3.46s      success  guest/tripleAndIncrement:0.0.1
</pre>

There are six activation records in this example, one matching the activation id returned on invocation (`22da217c8e3a4b799a217c8e3a0b79c4`) plus five additional records for activations _caused_ by this invocation. The _primary_ activation record is the last one in the list because it has the earliest start time.

The five additional activations are:

- one activation of the _triple_ action with input `{ value: 3 }` and output `{ value: 9 }`,
- one activation of the _increment_ action with input `{ value: 9 }` and output `{ value: 10 }`,
- three _secondary_ activations of the _tripleAndIncrement_ action.

### Causality

We say the invocation of the conductor action is the _cause_ of _component_ action invocations as well as _secondary_ activations of the conductor action. These activations are _derived_ activations.

The cause field of the _derived_ activation records is set to the id for the _primary_ activation record.

### Primary activations

The primary activation record for the invocation of a conductor action is a synthetic record similar to the activation record of a sequence action. The primary activation record summarizes the series of derived activations:

- its result is the result of the last action in the series (possibly unboxed, see below),
- its logs are the ordered list of component and secondary activations,
- its duration is the sum of the durations of these activations,
- its start time is less or equal to the start time of the first derived activation in the series,
- its end time is greater or equal to the end time of the last derived activation in the series.

```
wsk activation get 4f91f9ed0d874aaa91f9ed0d87baaa07
```
```
ok: got activation 4f91f9ed0d874aaa91f9ed0d87baaa07
{
    "namespace": "guest",
    "name": "composition",
    "version": "0.0.1",
    "subject": "guest",
    "activationId": "4f91f9ed0d874aaa91f9ed0d87baaa07",
    "start": 1516379705819,
    "end": 1516379707803,
    "duration": 457,
    "response": {
        "status": "success",
        "statusCode": 0,
        "success": true,
        "result": {
            "value": 12
        }
    },
    "logs": [
        "3624ad829d4044afa4ad829d40e4af60",
        "a1f58ade9b1e4c26b58ade9b1e4c2614",
        "47cb5aa5e4504f818b5aa5e450ef810f",
        "eaec119273d94087ac119273d90087d0",
        "fd89b99a90a1462a89b99a90a1d62a8e"
    ],
    "annotations": [
        {
            "key": "topmost",
            "value": true
        },
        {
            "key": "path",
            "value": "guest/tripleAndIncrement"
        },
        {
            "key": "conductor",
            "value": true
        },
        {
            "key": "kind",
            "value": "sequence"
        },
        {
            "key": "limits",
            "value": {
                "logs": 10,
                "memory": 256,
                "timeout": 60000
            }
        }
    "publish": false
}
```

If a component action itself is a sequence or conductor action, the logs contain only the id for the component activation. They do not contain the ids for the activations caused by this component. This is different from nested sequence actions.

### Secondary activations

The secondary activations of the conductor action are responsible for orchestrating the invocations of the component actions.

An invocation of a conductor action starts with a secondary activation and alternates secondary activations of this conductor action with invocations of the component actions. It normally ends with a secondary activation of the conductor action. In our example, the five derived activations are interleaved as follows:

 1. secondary _tripleAndIncrement_ activation,
 2. _triple_ activation,
 3. secondary _tripleAndIncrement_ activation,
 4. _increment_ activation,
 5. secondary _tripleAndIncrement_ activation.

Intuitively, secondary activations of the conductor action decide which component actions to invoke by running before, in-between, and after the component actions. In this example, the _tripleAndIncrement_ main function runs three times.

Only an internal error (invocation failure or timeout) may result in an even number of derived activations.

### Annotations

Primary activation records include the annotations `{ key: "conductor", value: true }` and `{ key: "kind", value: "sequence" }`. Secondary activation records and activation records for component actions include the annotation `{ key: "causedBy", value: "sequence" }`.

The memory limit annotation in the primary activation record reflects the maximum memory limit across the conductor action and the component actions.

## Continuations

A conductor action should return either an _error_ dictionary, i.e., a dictionary with an _error_ field, or a _continuation_, i.e., a dictionary with up to three fields `{ action, params, state }`. In essence, a continuation specifies what component action to invoke if any, as well as the parameters for this invocation, and the state to preserve until the next secondary activation of the conductor action.

The execution flow in our example is the following:

 1. The _tripleAndIncrement_ action is invoked on the input dictionary `{ value: 3 }`. It returns `{ action: 'triple', params: { value: 3 }, state: { $step: 1 } }` requesting that action _triple_ be invoked on _params_ dictionary `{ value: 3 }`.
 2. The _triple_ action is invoked on dictionary `{ value: 3 }` returning `{ value: 9 }`.
 3. The _tripleAndIncrement_ action is automatically reactivated. The input dictionary for this activation is `{ value: 9, $step: 1 }` obtained by combining the result of the _triple_ action invocation with the _state_ of the prior secondary _tripleAndIncrement_ activation (see below for details). It returns `{ action: 'increment', params: { value: 9 }, state: { $step: 2 } }`.
 4. The _increment_ action is invoked on dictionary `{ value: 9 }` returning `{ value: 10 }`.
 5. The _tripleAndIncrement_ action is automatically reactivated on dictionary `{ value: 10, $step: 2 }` returning `{ params: { value: 10 } }`.
 6. Because the output of the last secondary _tripleAndIncrement_ activation specifies no further action to invoke, this completes the execution resulting in the recording of the primary activation. The result of the primary activation is obtained from the result of the last secondary activation by extracting the value of the _params_ field: `{ value: 10 }`.

### Detailed specification

If a secondary activation returns an error dictionary, the conductor action invocation ends and the result of this activation (output and status code) are those of this secondary activation.

In a continuation dictionary, the _params_ field is optional and its value if present should be a dictionary. The _action_ field is optional and its value if present should be a string. The _state_ field is optional and its value if present should be a dictionary. If the value _v_ of the _params_ field is not a dictionary it is automatically boxed into dictionary `{ value: v }`. If the value _v_ of the _state_ field is not a dictionary it is automatically boxed into dictionary `{ state: v }`.

If the _action_ field is defined in the output of the conductor action, the runtime attempts to convert its value (irrespective of its type) into the fully qualified name of an action and invoke this action (using the default namespace if necessary). The action name should be a fully qualified name, which is of the form `/namespace/package-name/action-name` or `/namespace/action-name`. Failure to specify a fully qualified name may result in ambiguity or even a parsing error. There are four failure modes:

- parsing failure,
- resolution failure,
- entitlement check failure,
- internal error (invocation failure or timeout).

In the last failure scenario, the conductor action invocation ends with an _internal error_ status code and an error message describing the reason for the failure.

If there is no error, _action_ is invoked on the _params_ dictionary if specified (auto boxed if necessary) or if not on the empty dictionary. Upon completion of this invocation, the conductor action is activated again. The input dictionary for this activation is a combination of the output dictionary for the component action and the value of the _state_ field from the prior secondary conductor activation. Fields of the _state_ dictionary (auto boxed if necessary) are added to the output dictionary of the component activation, overriding values of existing fields if necessary.

In the first three failures scenarios, the conductor action is activated again. The input dictionary for this activation is a combination of an error object with an error message describing the reason for the failure and the value of the _state_ field from the prior secondary conductor activation (as in the previous scenario).

On the other hand, if the _action_ field is not defined in the output of the conductor action, the conductor action invocation ends. The output for the conductor action invocation is either the value of the _params_ field in the output dictionary of the last secondary activation if defined (auto boxed if necessary) or if absent the complete output dictionary.

## Limits

There are limits on the number of component action activations and secondary conductor activations in a conductor action invocation. These limits are assessed globally, i.e., if some components of a conductor action invocation are themselves conductor actions, the limits apply to the combined counts of activations across all the conductor action invocations.

The maximum number _n_ of permitted component activations is equal to the maximum number of components in a sequence action. It is configured via the same configuration parameter. The maximum number of secondary conductor activations is _2n+1_.

If the maximum number of permitted component activations is exceeded the conductor action is activated again. The input dictionary for this activation is a combination of an error object with an error message describing the reason for the failure and the value of the _state_ field from the prior secondary conductor activation.

If the maximum number of secondary conductor activations is exceeded, the conductor action invocation ends with an _application error_ status code and an error message describing the reason for the failure.

# Action compositions

Action compositions make it possible to dynamically build and invoke a series of actions. Action compositions are similar to sequences. However, whereas the components of a sequence must be specified before invoking the sequence, components of a composition can be decided as the composition is running.

## Example

Suppose we define an _increment_ action:

```
$ cat > increment.js
function main({ value }) { return { value: value + 1 } }
^D

$ wsk action create increment increment.js
```

We can use this _increment_ action in a composition as follows:

```
$ cat > composition.js
function main(params) {
    switch (params.$step || 0) {
        case 0: delete params.$step; return { params, action: 'increment', state: { $step: 1 } }
        case 1: delete params.$step; return { params, action: 'increment', state: { $step: 2 } }
        case 2: delete params.$step; return { params }
    }
}
^D

$ wsk action create composition composition.js -a conductor true
```

The key to making this action a composition is the _conductor_ annotation, which we discuss in the next section.

This example composition executes two _increment_ actions in a sequence:

```
$ wsk action invoke composition -br -p value 3
{
    "value": 5
}
```

In essence, this composition defines a program with three steps:

- step 0: invoke increment on the input dictionary,
- step 1: invoke increment on the output dictionary from step 1,
- step 2: return the output dictionary from step 2.

## Conductor Actions

An action composition is driven by a _conductor action_. A _conductor action_, or in short a _conductor_,  is an action with a _conductor_ annotation. The value of the annotation is irrelevant.

Because a conductor action is an action, it has all the attributes of an action (e.g., name, namespace, default parameters, limits...) and it can be managed as such, for instance using the `wsk action` CLI commands. It can be part of a package. It can be a web action. And so on.

A conductor should always output a JSON dictionary with up to three fields `{ params, action, state }`. The value of the field _params_ should be a JSON dictionary. The _action_ field of type string is optional. The _state_ field is optional and its value if present should be a JSON dictionary.

The execution of a composition alternates invocations of the conductor with invocations of the actions specified by the conductor. The conductor specifies an action to run (via the _action_ field of its output dictionary), the input dictionary for this action (_params_), as well as state to persist until the next step (_state_ field). If no action is specified, the composition ends.

In our example, there are five invocations: _composition_, _increment_, _composition_, _increment_, and _composition_. 

- The _composition_ action is initially invoked with dictionary `{ value: 3 }`. This invocation outputs `{ params: { value: 3 }, action: 'increment', state: { $step: 1 } }`.
- The _increment_ action is invoked with dictionary `{ value: 3 }` returning `{ value: 4 }`.
- The _composition_ action is invoked with dictionary `{ value: 4, $step: 1 }` obtained by adding the fields of `{ $step: 1 }` into `{ value: 4 }`. This invocation outputs `{ params: { value: 4 }, action: 'increment', state: { $step: 2 } }`.
- The _increment_ action is invoked with dictionary `{ value: 4 }` returning `{ value: 5 }`.
- The _composition_ action is invoked with dictionary `{ value: 5, $step: 2 }`. This invocation outputs `{ params: { value: 5 } }`.
- This terminates the execution of the action composition. The output dictionary for the composition is: `{ value: 5 }`.

For each conductor invocation (except for the first one) the _state_ produced by the prior conductor invocation is combined with the output dictionary of the composed action to obtain the input dictionary for this invocation. The combination function simply adds the fields of _state_ to the output dictionary of the action, overwriting fields if necessary.

## Activation Records

As with sequences, there is one activation record for each action invoked as part of a composition plus one activation record for composition itself.

```
$ wsk action invoke composition -p value 3
ok: invoked /_/composition with id 4f91f9ed0d874aaa91f9ed0d87baaa07

$ wsk activation get 4f91f9ed0d874aaa91f9ed0d87baaa07
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
            "value": 5
        }
    },
    "logs": [
        "3624ad829d4044afa4ad829d40e4af60",
        "a1f58ade9b1e4c26b58ade9b1e4c2614",
        "47cb5aa5e4504f818b5aa5e450ef810f",
        "eaec119273d94087ac119273d90087d0",
        "fd89b99a90a1462a89b99a90a1d62a8e"
    ],
    "annotations": [ ... ],
    "publish": false
}
```

The activation record for the composition contains the final result of the composition. The logs are the ordered list of activation ids for the actions invoked as part of the composition. There are five entry because the _composition_ action is invoked three times (for each step of the execution) in addition to the two invocations of the _increment_ action.

The duration for the composition is the sum of the durations of the invoked actions.

```
$ wsk activation list
activations
fd89b99a90a1462a89b99a90a1d62a8e composition         
eaec119273d94087ac119273d90087d0 increment           
3624ad829d4044afa4ad829d40e4af60 composition         
a1f58ade9b1e4c26b58ade9b1e4c2614 increment           
3624ad829d4044afa4ad829d40e4af60 composition         
4f91f9ed0d874aaa91f9ed0d87baaa07 composition   
```

For instance, the activation record for the first log entry corresponds to the initial _composition_ action invocation in the series:

```
$ wsk activation result 3624ad829d4044afa4ad829d40e4af60
{
    "action": "increment",
    "params": {
        "value": 3
    },
    "state": {
        "$step": 1
    }
}
```

As with sequences, an invocation of a composition returns the activation id for the composition, not the activation id for the first action invocation in the composition. As a result, a blocking invocation returns the result of the composition.

## Limitations

For now, sequences cannot be conductors. The conductor annotation is ignored on sequences.

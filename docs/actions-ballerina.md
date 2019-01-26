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

## Creating and invoking Ballerina actions

The process of creating Ballerina actions is similar to that of [other actions](actions.md#the-basics).
The following sections guide you through creating and invoking a Ballerina action.

Ballerina actions are executed using Ballerina [0.990.2](https://ballerina.io/downloads). You will need
a compatible version of the compiler locally available to generate the executable. Without the Ballerina compiler,
you cannot create an OpenWhisk action.

An action is simply a top-level Ballerina function which accepts and returns a JSON object. For example, create a file called `hello.bal`
with the following source code:

```ballerina
import ballerina/io;

public function main(json data) returns json {
  json? name = data.name;
  if (name == null) {
    return { greeting: "Hello stranger!" };
  } else {
    return { greeting: "Hello " + name.toString() + "!" };
  }
}
```

The entry method for the action is `main` by default but may be specified explicitly when creating
the action with the `wsk` CLI using `--main`, as with any other action type. It is important to note
that the Ballerina compiler expects the presence of a function called `main` to generate the executable.
Hence, when using alternate entry points, your source file must still include a place holder called `main`.

You can create an OpenWhisk action called `bello` from the function above as follows:

```
# generate the .balx file first
ballerina build hello.bal

# use the .balx file to create the action
wsk action create bello hello.balx --kind ballerina:0.990
```

The CLI does not yet automatically infer the type of the action from the source file extension.
So you must specify the kind explicitly. For `.balx` source files, the action currently runs using the Ballerina 0.990.2 runtime.

Action invocation is the same for Ballerina actions as it is for [any other action](actions.md#the-basics).

```
wsk action invoke --result bello --param name World
```

```json
{
  "greeting": "Hello World!"
}
```

Find out more about parameters in the [Working with parameters](./parameters.md) section.

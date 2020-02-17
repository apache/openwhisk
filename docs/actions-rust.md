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

## Creating and invoking Rust actions

The process of creating Rust actions is similar to that of [other actions](actions.md#the-basics).
The following sections guide you through creating and invoking a single Rust action,
and demonstrate how to bundle multiple Rust files and third party dependencies.

An example action Rust action is simply a top-level function.
For example, create a file called `hello.rs` with the following source code:

```Rust
extern crate serde_json;

use serde_derive::{Deserialize, Serialize};
use serde_json::{Error, Value};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
struct Input {
    #[serde(default = "stranger")]
    name: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
struct Output {
    greeting: String,
}

fn stranger() -> String {
    "stranger".to_string()
}

pub fn main(args: Value) -> Result<Value, Error> {
    let input: Input = serde_json::from_value(args)?;
    let output = Output {
        greeting: format!("Hello, {}", input.name),
    };
    serde_json::to_value(output)
}
```

Rust actions are mainly composed by a `main` function that accepts a JSON `serdes Value` as input and returns a `Result` including a JSON `serde Value`.

The entry method for the action is `main` by default but may be specified explicitly when creating
the action with the `wsk` CLI using `--main`, as with any other action type.

You can create an OpenWhisk action called `helloRust` from this function as follows:

```
wsk action create helloRust hello.rs
```
The CLI automatically infers the type of the action from the source file extension.
For `.rs` source files, the action runs using a Rust v1.34 runtime.

Action invocation is the same for Rust actions as it is for any other actions:

```
wsk action invoke --result helloRust --param name World
```

```json
  {
      "greeting": "Hello World!"
  }
```

Find out more about parameters in the [Working with parameters](./parameters.md) section.

## Packaging Rust actions in zip files

If your action needs external dependencies, you need to provide a zip file including your source and your cargo file with all your dependencies.
The filename of the source file containing the entry point (e.g., `main`) must be `lib.rs`.
The folder structure should be as follows:
```
|- Cargo.toml
|- src
    |- lib.rs
```
Here is an example of a Cargo.toml file
```
[package]
name = "actions"
version = "0.1.0"
authors = ["John Doe <john@doe.com>"]
edition = "2018"

[dependencies]
serde_json = "1.0"
serde = "1.0"
serde_derive = "1.0"
```

To zip your folder:

```bash
zip -r helloRust.zip Cargo.toml src
```

and then create the action:

```bash
wsk action create helloRust --kind rust:1.34 helloRust.zip
```

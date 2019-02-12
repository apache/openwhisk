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

## Creating and invoking Ruby actions

The process of creating Ruby actions is similar to that of [other actions](actions.md#the-basics).
The following sections guide you through creating and invoking a single Ruby action,
and demonstrate how to bundle multiple Ruby files and third party dependencies.

Ruby actions are executed using Ruby 2.5. To use this runtime, specify the `wsk` CLI parameter
`--kind ruby:2.5` when creating or updating an action. This is the default when creating an action
with file that has a `.rb` extension.

An action is simply a top-level Ruby method. For example, create a file called `hello.rb`
with the following source code:

```ruby
def main(args)
  name = args["name"] || "stranger"
  greeting = "Hello #{name}!"
  puts greeting
  { "greeting" => greeting }
end
```

Ruby actions always consume a Hash and return a Hash.
The entry method for the action is `main` by default but may be specified explicitly
when creating the action with the `wsk` CLI using `--main`, as with any other action type.

You can create an OpenWhisk action called `hello_ruby` from this function as follows:

```
wsk action create hello_ruby hello.rb
```

The CLI automatically infers the type of the action from the source file extension.
For `.rb` source files, the action runs using a Ruby 2.5 runtime.

Action invocation is the same for Ruby actions as it is for [any other action](actions.md#the-basics).

```
wsk action invoke --result hello_ruby --param name World
```

```json
{
  "greeting": "Hello World!"
}
```

Find out more about parameters in the [Working with parameters](./parameters.md) section.

## Packaging Ruby actions in zip files

You can package a Ruby action along with other files and dependent packages in a zip file.
The filename of the source file containing the entry point (e.g., `main`) must be `main.rb`.
For example, to create an action that includes a second file called `helper.rb`,
first create an archive containing your source files:

```bash
zip -r hello_ruby.zip main.rb helper.rb
```

and then create the action:

```bash
wsk action create hello_ruby --kind ruby:2.5 hello_ruby.zip
```

A few Ruby gems such as `mechanize` and `jwt` are available in addition to the default and bundled gems.
You can use arbitrary gems so long as you use zipped actions to package all the dependencies.

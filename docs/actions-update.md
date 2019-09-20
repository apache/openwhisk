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
## Updating Action Language Runtimes

OpenWhisk supports [several languages and runtimes](actions.md#languages-and-runtimes) that can be made
available for usage in an OpenWhisk deployment. This is done via the [runtimes manifest](actions-new.md#the-runtimes-manifest).

Over time, you may need to do one or more of the following:

* Update runtimes to address security issues - for example, the latest code level of Node.js 10.
* Remove runtime versions that are no longer supported - for example, Node.js 6.
* Add more languages due to user demand.
* Remove languages that are no longer needed.

While adding and updating languages and runtimes is pretty straightforward, removing or renaming languages and runtimes
requires some caution to prevent problems with preexisting actions.

### Updating runtimes

Follow these steps to update a particular runtime kind:

1. Update the runtime's container image.
2. Update the corresponding `image` property in the [runtimes manifest](actions-new.md#the-runtimes-manifest) to use the new container image.
3. Restart / re-deploy controllers and invokers such that they pick up the changed runtimes manifest.

Already existing actions of the changed runtime kind will immediately use the new container image when the invoker creates a new action container.

Obviously, this approach should only be used if existing actions do not break with the new container image. If a new container image may break existing actions, consider introducing a new runtime kind instead.

### Removing runtimes

Follow these steps to remove a particular runtime kind under the assumption that actions with the runtime kind exist in the system. Clearly, the steps below should be spaced out in time to give action owners time to react.

1. Deprecate the runtime kind by setting `"deprecated": true` in the [runtimes manifest](actions-new.md#the-runtimes-manifest). This setting prevents new actions from being created with the deprecated action kind. In addition, existing actions cannot be changed to the deprecated action kind any more.
2. Ask owners of existing actions affected by the runtime kind to remove or update their actions to a different action kind.
3. Create an automated process that identifies all actions with the runtime kind to be removed in the system's action artifact store. Either automatically remove these actions or change to a different runtime kind.
4. Once the system's action artifact store does not contain actions with the runtime kind to be removed, remove the runtime kind from the [runtimes manifest](actions-new.md#the-runtimes-manifest).
5. Remove the runtime kind from the list of known kinds in the `ActionExec` object of the [controller API's Swagger definition](../core/controller/src/main/resources/apiv1swagger.json).

If you remove a runtime kind from the [runtimes manifest](actions-new.md#the-runtimes-manifest), all actions that still use the removed runtime kind can no longer be read, updated, deleted or invoked.

If the system's action artifact store does not contain any action with the runtime kind to be removed, there is no need for the deprecation and migration steps.

### Renaming runtimes

Renaming a runtime kind actually means removing the runtime kind and adding a different runtime kind. Follow the steps for [Removing runtimes](removing-runtimes) and [Updating runtimes](updating-runtimes).

### Updating languages

* The process of adding a new language is described in [Adding Action Language Runtimes](actions-new.md).
* Adding new runtime kinds to a language family needs no special considerations - just add it to the [runtimes manifest](actions-new.md#the-runtimes-manifest).

### Removing languages

You need to follow the steps described in [Removing runtimes](removing-runtimes) for all runtime kinds in the language family.

### Renaming languages

Renaming a language family actually means removing the language family and adding a different family. Follow the steps for [Removing languages](removing-languages) and [Updating languages](updating-languages).

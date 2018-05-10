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
# Potential Future Enhancements

## Web actions potential future enhancements

It is worthwhile to consider how to make several of the features currently offered by web actions more generally available to all action. It may also be useful to generalize the `web-export` annotation to provide additional control over which HTTP methods an action is prepared the handle and similarly which content types it supports. One can imagine doing this with a richer `web-export` annotation which may define at least these additional properties:

1. `methods`: array of HTTP methods accepted.
2. `extensions`: array of supported extensions.
3. `authentication`: one of `{"none", "builtin"}` where `none` is for anonymous access and `builtin` for OpenWhisk authentication.

As in `-a web-export '{ "methods": ["get", "post"], "extensions": ["http"], "authentication": "none" }'` for a web action that only accepts `get` and `post` requests, handled `.http` extensions only, and permits anonymous access. A richer set of annotations will allow the controller to reject requests early if a web action does not support a particular method for example. The current implementation will accept `get`, `post`, `put` and `delete` HTTP methods without discrimination for any web action.

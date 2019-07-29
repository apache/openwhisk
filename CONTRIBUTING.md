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
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0)

# Contributing to Apache OpenWhisk

Anyone can contribute to the OpenWhisk project and we welcome your contributions.

There are multiple ways to contribute: report bugs, improve the docs, and
contribute code, but you must follow these prerequisites and guidelines:

 - [Contributor License Agreement](#contributor-license-agreement)
 - [Raising issues](#raising-issues)
 - [Coding Standards](#coding-standards)

### Contributor License Agreement

All contributors must sign and submit an Apache CLA (Contributor License Agreement).

Instructions on how to do this can be found here:
[http://www.apache.org/licenses/#clas](http://www.apache.org/licenses/#clas)

Sign the appropriate CLA and submit it to the Apache Software Foundation (ASF) secretary. You will receive a confirmation email from the ASF and be added to
the following list: http://people.apache.org/unlistedclas.html.  Once your name is on this list, you are done and your PR can be merged.

Project committers will use this list to verify pull requests (PRs) come from contributors that have signed a CLA.

We look forward to your contributions!

## Raising issues

Please raise any bug reports or enhancement requests on the respective project repository's GitHub issue tracker. Be sure to search the
list to see if your issue has already been raised.

A good bug report is one that make it easy for us to understand what you were trying to do and what went wrong.
Provide as much context as possible so we can try to recreate the issue.

A good enhancement request comes with an explanation of what you are trying to do and how that enhancement would help you.

### Discussion

Please use the project's developer email list to engage our community:
[dev@openwhisk.apache.org](dev@openwhisk.apache.org)

In addition, we provide a "dev" Slack team channel for conversations at:
https://openwhisk-team.slack.com/messages/dev/

### Coding standards

Please ensure you follow the coding standards used throughout the existing
code base. Some basic rules include:

 - all files must have the Apache license in the header.
 - all PRs must have passing builds for all operating systems.
 - the code is correctly formatted as defined in the [Scalariform plugin properties](tools/eclipse/scala.properties). If you use IntelliJ for development this [page](https://plugins.jetbrains.com/plugin/7480-scalariform) describes the setup and configuration of the plugin.

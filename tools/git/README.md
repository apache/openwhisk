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

# Purpose

This directory contains shell scripts to be used in conjunction with `git` operations.

## Pre-commit hooks

This directory contains following `pre-commit` hooks. Read `man githooks` for details
about `pre-commit` hooks and how to install / use them.

### Scala source formatting

Any of the following `pre-commit` hooks can be used to format all staged Scala source files (`*.scala`)
according to project standards. Said files are changed in-place and re-staged so that committed
files are properly formatted.

* `pre-commit-scalafmt-gradlew.sh`: Use Gradle wrapper for formatting.
* `pre-commit-scalafmt-native.sh`: Use `scalafmt` command for formatting. Less overhead and thus,
  faster than Gradle wrapper approach. You have to install `scalafmt` command - see http://scalameta.org/scalafmt/.

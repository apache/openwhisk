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

Utility image for executing bash scripts
================

The `script-runner` base image is intended to be used to execute
utility tasks defined as bash scripts. The script to be executed
should be mounted in the container as /task/myTask.sh by
Kubernetes/Docker/Mesos.  The `wsk` cli is installed in /usr/local/bin
to make it easily available to the scripts.


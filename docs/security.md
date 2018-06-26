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

# Securing your actions

The actions that you create will run in a sandboxed environment, namely a container. The code that you
write nonetheless should follow best practices to ensure that the code is reasonably secure against remote
code exploits and malicious inputs. You should also be cognizant of the packages you bundle and check them
routinely for vulnerabilities.

There are several considerations to be mindful of when authoring actions:

- **Sanitize Function Arguments:** Every invocation of the action receives input arguments which may be from untrusted sources.
- **Check Dependencies for Vulnerabilities:** When bundling third party dependencies, you should be aware of any vulnerabilities you inherit.
- **Authenticate Requests:** When using [web actions](webactions.md#securing-web-actions), you can enable built-in authentication to reject unwanted requests.
- **Seal Parameters:** Parameters with pre-defined values may be sealed when used with [web actions](webactions.md#protected-parameters) to prevent parameter hijacking.

Actions which are vulnerable to code injection attacks or parameter hijacking could end up leaking bound
action parameters, or worse persisting malicious code within the sandbox for the lifetime of the function
execution. Moreover, an action sandbox may be reused for more than one function invocation, and hence an
attacker could persist their code for the lifetime of the sandbox as well.

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

# Build Failures

This guide details problems that may occur during the OpenWhisk build process.

## Dependency download failures from JCenter

Occasionally build failures occur when the JCenter repository is experiencing problems. An example of such a failure
is shown below.

```
FAILURE: Build failed with an exception.
* What went wrong:
A problem occurred configuring root project 'openwhisk'.
> Could not resolve all files for configuration ':classpath'.
   > Could not download groovy-all.jar (org.codehaus.groovy:groovy-all:2.4.7)
      > Could not get resource 'https://jcenter.bintray.com/org/codehaus/groovy/groovy-all/2.4.7/groovy-all-2.4.7.jar'.
         > Could not GET 'https://jcenter.bintray.com/org/codehaus/groovy/groovy-all/2.4.7/groovy-all-2.4.7.jar'.
            > Connect to akamai.bintray.com:443 [akamai.bintray.com/23.45.134.89] failed: Connection timed out (Connection timed out)
```

To determine if this error is indeed related to JCenter issues, check the JFrog Bintray
[status page](http://status.bintray.com/).

#!/bin/bash
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

if [ -z "$TRAVIS_BRANCH" ]; then
    echo 'Could not determine diffs'
    exit
fi

# exclude job if it only touches documentation
result=$(git diff --name-only HEAD...$TRAVIS_BRANCH | grep -v .md)
status=$?
if [ $status == 1 ]; then
    # no relevant changes detected'
    echo 'SKIP'
else
    echo 'Detected relevant changes'
    echo $result
fi

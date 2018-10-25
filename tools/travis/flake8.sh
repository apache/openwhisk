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

pip3 install --user --upgrade flake8

# These files do not have a .py extension so flake8 will not scan them
declare -a PYTHON_FILES=("."
                         "./tools/admin/wskadmin"
                         "./tools/build/citool"
                         "./tools/build/redo")

echo 'Flake8: first round (fast fail) stops the build if there are any Python 3 syntax errors...'
for i in "${PYTHON_FILES[@]}"
do
    flake8 "$i" --select=E999,F821 --statistics
    RETURN_CODE=$?
    if [ $RETURN_CODE != 0 ]; then
        echo 'Flake8 found Python 3 syntax errors above. See: https://docs.python.org/3/howto/pyporting.html'
        exit $RETURN_CODE
    fi
done

echo 'Flake8: second round to find any other stylistic issues...'
for i in "${PYTHON_FILES[@]}"
do
    flake8 "$i" --ignore=E,W503,W504,W605 --max-line-length=127 --statistics
done

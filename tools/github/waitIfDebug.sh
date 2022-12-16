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

if ! test -e .ngrok.log
then exit 0
fi
echo "You have an hour to debug this build."
echo "Do touch /tmp/continue to continue."
echo "Do touch /tmp/abort to abort."

EXIT=0
for i in $(seq 1 60)
do
   if test -e /tmp/continue ; then EXIT=0 ; break ; fi
   if test -e /tmp/abort ; then EXIT=1 ; break ; fi
   echo "$i/60 still waiting..."
   sleep 60
done

killall ngrok
rm -f .ngrok.log /tmp/continue /tmp/abort
exit $EXIT

#!/bin/bash

#
# Copyright 2015-2016 IBM Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

WHISKPROPS=$1
: ${WHISKPROPS:?"WHISKPROPS must be set and non-empty"}

DOCKER_ENDPOINTS=`cat "$WHISKPROPS" | egrep "docker.endpoint" | sed -e "s/.*=//" | sed -e "s/:.*//" | sort | uniq`
INVOKERS_ONE_LINE=`cat "$WHISKPROPS" | egrep "invoker.hosts=" | sed -e "s/.*=//" | sed -e "s/:.*//"`
INVOKERS=(${INVOKERS_ONE_LINE//,/\\n})
ADDITIONAL_ONE_LINE=`cat "$WHISKPROPS" | egrep "additional.hosts=" | sed -e "s/.*=//" | sed -e "s/:.*//"`
ADDITIONAL=(${ADDITIONAL_ONE_LINE//,/\\n})

HOSTS_UNIQUE=`printf "$INVOKERS\n$DOCKER_ENDPOINTS\n$ADDITIONAL" | sort | uniq`

printf "$HOSTS_UNIQUE\n"

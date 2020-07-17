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

set -x

# Setup for overhead test: create action, create trigger, and create a number of rules as required.
# This script performs both setup and teardown
# Designed to be an idempotent operation (can be applied repeatedly for the same result)
# Usage: setup.sh [op] [ratio] <wsk global flags>
# op - MANDATORY. "s" for setup, "t" for teardown
# ratio - MANDATORY. ratio as defined in README.md
# wsk global flags - OPTIONAL. Global flags for the wsk command (e.g. for specifying non-default wsk API host, auth, etc)

MAXRULES=30	# assume no more than 30 rules per trigger max
op=$1		# s for setup, t for teardown
count=$2	# ratio for rules
delcount=$count # For teardown, delete ratio rules. For setup, delete MAXRULES
if [ "$op" = "s" ]; then
	delcount=$MAXRULES
fi

shift 2
wskparams="$@"	# All other parameters are assumed to be OW-specific


function remove_assets() {

	# Delete rules
	for i in $(seq 1 $delcount); do
    		wsk rule delete testRule_$i $@;
	done

	# Delete trigger
	wsk trigger delete testTrigger $@

	# Delete action
	wsk action delete testAction $@

}


function deploy_assets() {

	# Create action
	wsk action create testAction testAction.js --kind nodejs:default $@

	# Create trigger after deleting it
	wsk trigger create testTrigger $@

	# Create rules
	for i in $(seq 1 $count); do
    		wsk rule create testRule_$i testTrigger testAction $@;
	done

}


# Always start with removal of existing assets
remove_assets $wskparams

# If setup requested, deploy new assets
if [ "$op" = "s" ]; then
	deploy_assets $wskparams
fi


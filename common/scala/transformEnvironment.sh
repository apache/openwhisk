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

#
# Transforms environment variables starting with `prefix` to kebab-cased JVM system properties
#
# "_"           becomes "."
# "camelCased"  becomes "camel-cased"
# "PascalCased" stays   "PascalCased" -> classnames stay untouched
#
# Examples:
# CONFIG_whisk_loadbalancer_invokerBusyThreshold -> -Dwhisk.loadbalancer.invoker-busy-threshold
# CONFIG_akka_remote_netty_tcp_bindPort          -> -Dakka.remote.netty.tcp.bind-port
# CONFIG_whisk_spi_LogStoreProvider              -> -Dwhisk.spi.LogStoreProvider
#

prefix="CONFIG_"
configVariables=$(compgen -v | grep $prefix)

props=()

if [ -n "$OPENWHISK_CONFIG" ]; then
    config="$OPENWHISK_CONFIG"
elif [ -n "$OPENWHISK_ENCODED_CONFIG" ]; then
    config=$(echo "$OPENWHISK_ENCODED_CONFIG" | base64 -d)
fi

if [ -n "$config" ]
then
    location="$HOME/config.conf"
    printf "%s" "$config" > "$location"
    props+=("-Dconfig.file='$location'")
fi

for var in $configVariables
do
    value=$(printenv "$var")
    #allow us to dereference environment variables, e.g. CONFIG_some_key=$SOME_ENV_VAR
    if [[ $value == \$* ]] # iff the value starts with $
    then
        varname=${value:1} # drop the starting '$'
        value2=${!varname} # '!' dereferences the variable
        if [ ! -z "$value2" ]
        then
            value=$value2 # replace $value with $value2 (the dereferenced value)
        fi
    fi


    if [ ! -z "$value" ]
    then
        sansConfig=${var#$prefix} # remove the CONFIG_ prefix
        parts=${sansConfig//_/ } # "split" the name by replacing '_' with ' '

        transformedParts=()
        for part in $parts
        do
            if [[ $part =~ ^[A-Z] ]] # if the current part starts with an uppercase letter (is PascalCased)
            then
                transformedParts+=($part) # leave it alone
            else
                transformedParts+=($(echo "$part" | sed -r 's/([a-z0-9])([A-Z])/\1-\L\2/g')) # rewrite camelCased to kebab-cased
            fi
        done

        key=$(IFS=.; echo "${transformedParts[*]}") # reassemble the parts delimited by a '.'
        props+=("-D$key='$value'") # assemble a JVM system property
    fi
done

echo "${props[@]}"

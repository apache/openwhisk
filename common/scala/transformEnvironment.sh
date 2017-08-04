#!/bin/bash

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

for var in $configVariables
do
    value=$(printenv "$var")
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
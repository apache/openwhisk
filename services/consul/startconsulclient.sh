#!/bin/bash

#start consul
/bin/consul agent -config-file=/consul/config/client-config.json  >> /logs/consulclient.log "$@" 2>&1 

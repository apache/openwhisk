#!/bin/bash

#start consul
/bin/consul agent -config-file=/consul/config/server-config.json -data-dir=/consul/data  >> /logs/consul.log 2>&1 

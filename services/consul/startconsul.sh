#!/bin/bash

#start consul
/bin/consul agent -config-dir=/consul/config  >> consul/logs/consul.log 2>&1 &

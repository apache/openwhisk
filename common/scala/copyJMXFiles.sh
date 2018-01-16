#!/bin/bash

if [[ $( ls /conf/jmxremote.* 2> /dev/null ) ]]
then
  mv /conf/jmxremote.* /root
  chmod 600 /root/jmxremote.*
fi
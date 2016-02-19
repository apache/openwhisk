#!/bin/bash

#CONSULCMD="/startconsul.sh"
#echo $CONSULCMD 
#$CONSULCMD

RUN=scala
MAIN=whisk.core.loadBalancer.LoadBalancer
JAR=loadBalancer.jar
LIB=loadBalancer-libs

CLASSPATH=$(JARS=("$LIB"/*.jar); IFS=:; echo "${JARS[*]}")
CMD="$RUN -classpath $JAR:$CLASSPATH $MAIN $@"

STDOUT_ERR_REDIRECT=" >> /logs/${COMPONENT_NAME}_logs.log 2>&1"
bash -c "$CMD $STDOUT_ERR_REDIRECT"

#!/bin/bash


RUN=scala
MAIN=whisk.core.controller.Controller
JAR=controller.jar
LIB=controller-libs
HEAP=-J-Xmx2g

CLASSPATH=$(JARS=("$LIB"/*.jar); IFS=:; echo "${JARS[*]}")
CMD="$RUN -classpath $JAR:$CLASSPATH $HEAP $MAIN $@"

STDOUT_ERR_REDIRECT=" >> /logs/${COMPONENT_NAME}_logs.log 2>&1"
bash -c "$CMD $STDOUT_ERR_REDIRECT"

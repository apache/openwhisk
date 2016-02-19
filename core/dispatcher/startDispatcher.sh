#!/bin/bash

RUN=scala
MAIN=whisk.core.dispatcher.Dispatcher
JAR=dispatcher.jar
LIB=dispatcher-libs

CLASSPATH=$(JARS=("$LIB"/*.jar); IFS=:; echo "${JARS[*]}")
CMD="$RUN -classpath $JAR:$CLASSPATH $MAIN $@"

REDIRECT=" >> /logs/${COMPONENT_NAME}_logs.log 2>&1"
bash -c "$CMD $REDIRECT"

#!/bin/bash
#
# Usage copyLogs.sh <whisk.home>
# 

WHISK_HOME=$1
: ${WHISK_HOME:?"WHISK_HOME must be set and non-empty"}
: ${WHISK_LOGS_DIR:?"WHISK_LOGS_DIR must be set and non-empty"}
LOGDIR=$WHISK_HOME/logs

cp -r $WHISK_LOGS_DIR/* $LOGDIR

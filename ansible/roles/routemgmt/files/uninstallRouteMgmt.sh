#!/bin/bash
# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

########
#
# use the command line interface to install standard actions deployed
# automatically
#
# To run this command
# ./uninstallRouteMgmt.sh  <AUTH> <APIHOST> <NAMESPACE> <WSK_CLI>
# AUTH, APIHOST and NAMESPACE are found in $HOME/.wskprops
# WSK_CLI="$OPENWHISK_HOME/bin/wsk"

set -e

if [ $# -eq 0 ]
then
echo "Usage: ./uninstallRouteMgmt.sh AUTHKEY APIHOST NAMESPACE PATH_TO_WSK_CLI"
fi

AUTH="$1"
APIHOST="$2"
NAMESPACE="$3"
WSK_CLI="$4"

# If the auth key file exists, read the key in the file. Otherwise, take the
# first argument as the key itself.
if [ -f "$AUTH" ]; then
    AUTH=`cat $AUTH`
fi

if [ ! -f $WSK_CLI ]; then
    echo $WSK_CLI is missing
    exit 1
fi

export WSK_CONFIG_FILE= # override local property file to avoid namespace clashes

function deleteAction
{
  # The "get" command will fail if the resource does not exist, so use "set +e" to avoid exiting the script
  set +e
  $WSK_CLI -i --apihost "$APIHOST" action get --auth "$AUTH" "$1"
  RC=$?
  if [ $RC -eq 0 ]
  then
    set -e
    $WSK_CLI -i --apihost "$APIHOST" action delete --auth "$AUTH" "$1"
  fi
  set -e
}

function deletePackage
{
  # The "get" command will fail if the resource does not exist, so use "set +e" to avoid exiting the script
  set +e
  $WSK_CLI -i --apihost "$APIHOST" package get --auth "$AUTH" "$1" -s
  RC=$?
  if [ $RC -eq 0 ]
  then
    set -e
    $WSK_CLI -i --apihost "$APIHOST" package delete --auth "$AUTH" "$1"
  fi
}

# Delete actions, then the package.  The order is important (can't delete a package that contains an action)!

echo Deleting routemgmt actions
deleteAction $NAMESPACE/routemgmt/getApi
deleteAction $NAMESPACE/routemgmt/createApi
deleteAction $NAMESPACE/routemgmt/deleteApi

echo Deleting routemgmt package - but only if it exists
deletePackage $NAMESPACE/routemgmt

echo Deleting apimgmt actions
deleteAction $NAMESPACE/apimgmt/getApi
deleteAction $NAMESPACE/apimgmt/createApi
deleteAction $NAMESPACE/apimgmt/deleteApi

echo Deleting apimgmt package - but only if it exists
deletePackage $NAMESPACE/apimgmt

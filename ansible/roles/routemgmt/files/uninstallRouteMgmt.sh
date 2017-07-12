#!/bin/bash
#
# use the command line interface to install standard actions deployed
# automatically
#
# To run this command
# ./installRouteMgmt.sh <SSL_SWITCH> <AUTH> <CERT_FILE> <KEY_FILE> <APIHOST> <NAMESPACE> <WSK_CLI>
# AUTH, APIHOST and NAMESPACE are found in $HOME/.wskprops
# WSK_CLI="$OPENWHISK_HOME/bin/wsk"

set -e
set -x

if [ $# -eq 0 ]
then
echo "Usage: ./uninstallRouteMgmt.sh SSL_SWITCH AUTHKEY CERT_FILE KEY_FILE APIHOST NAMESPACE PATH_TO_WSK_CLI"
fi

SSL_SWITCH="$1"
AUTH="$2"
CERT_FILE="$3"
KEY_FILE="$4"
APIHOST="$5"
NAMESPACE="$6"
WSK_CLI="$7"

# If the auth key file exists, read the key in the file. Otherwise, take the
# first argument as the key itself.
if [ -f "$AUTH" ]; then
    AUTH=`cat $AUTH`
fi

AUTH="--auth $AUTH"
if [ "$SSL_SWITCH" != "off" ]; then
    AUTH="--cert $CERT_FILE --key $KEY_FILE"
fi

export WSK_CONFIG_FILE= # override local property file to avoid namespace clashes

function deleteAction
{
  # The "get" command will fail if the resource does not exist, so use "set +e" to avoid exiting the script
  set +e
  $WSK_CLI -i --apihost "$APIHOST" action get $AUTH "$1"
  RC=$?
  if [ $RC -eq 0 ]
  then
    set -e
    $WSK_CLI -i --apihost "$APIHOST" action delete $AUTH "$1"
  fi
  set -e
}

function deletePackage
{
  # The "get" command will fail if the resource does not exist, so use "set +e" to avoid exiting the script
  set +e
  $WSK_CLI -i --apihost "$APIHOST" package get $AUTH "$1" -s
  RC=$?
  if [ $RC -eq 0 ]
  then
    set -e
    $WSK_CLI -i --apihost "$APIHOST" package delete $AUTH "$1"
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

#!/bin/bash
#
# use the command line interface to install standard actions deployed
# automatically
#
# To run this command
# ./installRouteMgmt.sh  <AUTH> <APIHOST> <NAMESPACE> <WSK_CLI>
# AUTH, APIHOST and NAMESPACE are found in $HOME/.wskprops
# WSK_CLI="$OPENWHISK_HOME/bin/wsk"

set -e
set -x

if [ $# -eq 0 ]
then
echo "Usage: ./installRouteMgmt.sh AUTHKEY APIHOST NAMESPACE PATH_TO_WSK_CLI"
fi

AUTH="$1"
APIHOST="$2"
NAMESPACE="$3"
WSK_CLI="$4"
DB_USERNAME="$5"
DB_PASSWORD="$6"

WHISKPROPS_FILE="$OPENWHISK_HOME/whisk.properties"
DB_HOST=`fgrep db.host= $WHISKPROPS_FILE | cut -d'=' -f2`
DB_PORT=`fgrep db.port= $WHISKPROPS_FILE | cut -d'=' -f2`
DB_PROTOCOL=`fgrep db.protocol= $WHISKPROPS_FILE | cut -d'=' -f2`
DB_APIGW=`fgrep db.whisk.apigw= $WHISKPROPS_FILE | cut -d'=' -f2`
GW_USER=`fgrep apigw.auth.user= $WHISKPROPS_FILE | cut -d'=' -f2`
GW_PWD=`fgrep apigw.auth.pwd= $WHISKPROPS_FILE | cut -d'=' -f2-`
GW_HOST=`fgrep apigw.host= $WHISKPROPS_FILE | cut -d'=' -f2`

# If the auth key file exists, read the key in the file. Otherwise, take the
# first argument as the key itself.
if [ -f "$AUTH" ]; then
    AUTH=`cat $AUTH`
fi

export WSK_CONFIG_FILE= # override local property file to avoid namespace clashes

echo Installing routemgmt package.
$WSK_CLI -i --apihost "$APIHOST" package update --auth "$AUTH"  --shared no "$NAMESPACE/routemgmt" \
-a description "This package manages the gateway API configuration." \
-a meta true \
-a get getApi \
-a post createRoute \
-a delete deleteApi \
-p host $DB_HOST \
-p port $DB_PORT \
-p protocol $DB_PROTOCOL \
-p username $DB_USERNAME \
-p password $DB_PASSWORD \
-p dbname $DB_APIGW \
-p gwUser "$GW_USER" \
-p gwPwd "$GW_PWD" \
-p gwUrl "$GW_HOST"


echo Installing routemgmt actions
$WSK_CLI -i --apihost "$APIHOST" action update --auth "$AUTH" "$NAMESPACE/routemgmt/createRoute" "$OPENWHISK_HOME/core/routemgmt/createRoute.js" \
-a description 'Create an API route'

$WSK_CLI -i --apihost "$APIHOST" action update --auth "$AUTH" "$NAMESPACE/routemgmt/createApi" "$OPENWHISK_HOME/core/routemgmt/createApi.js" \
-a description 'Create an API configuration'

$WSK_CLI -i --apihost "$APIHOST" action update --auth "$AUTH" "$NAMESPACE/routemgmt/deleteApi" "$OPENWHISK_HOME/core/routemgmt/deleteApi.js" \
-a description 'Delete the specified API configuration'

$WSK_CLI -i --apihost "$APIHOST" action update --auth "$AUTH" "$NAMESPACE/routemgmt/getApi" "$OPENWHISK_HOME/core/routemgmt/getApi.js" \
-a description 'Retrieve the specified API configuration (in JSON format)'

$WSK_CLI -i --apihost "$APIHOST" action update --auth "$AUTH" "$NAMESPACE/routemgmt/activateApi" "$OPENWHISK_HOME/core/routemgmt/activateApi.js" \
-a description 'Activate the specified API'

$WSK_CLI -i --apihost "$APIHOST" action update --auth "$AUTH" "$NAMESPACE/routemgmt/deactivateApi" "$OPENWHISK_HOME/core/routemgmt/deactivateApi.js" \
-a description 'Deactivate the specified API'

$WSK_CLI -i --apihost "$APIHOST" action update --auth "$AUTH" "$NAMESPACE/routemgmt/syncApi" "$OPENWHISK_HOME/core/routemgmt/syncApi.js" \
-a description 'Synchronize the API configuration with the API gateway'


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

WHISKPROPS_FILE="$OPENWHISK_HOME/whisk.properties"
DB_HOST=`fgrep db.host= $WHISKPROPS_FILE | cut -d'=' -f2`
DB_PORT=`fgrep db.port= $WHISKPROPS_FILE | cut -d'=' -f2`
DB_PROTOCOL=`fgrep db.protocol= $WHISKPROPS_FILE | cut -d'=' -f2`
DB_USERNAME=`fgrep db.username= $WHISKPROPS_FILE | cut -d'=' -f2`
DB_PASSWORD=`fgrep db.password= $WHISKPROPS_FILE | cut -d'=' -f2`
DB_GWAPI=`fgrep db.whisk.gwapi= $WHISKPROPS_FILE | cut -d'=' -f2`

# If the auth key file exists, read the key in the file. Otherwise, take the
# first argument as the key itself.
if [ -f "$AUTH" ]; then
    AUTH=`cat $AUTH`
fi

export WSK_CONFIG_FILE= # override local property file to avoid namespace clashes

echo Installing routemgmt package.
$WSK_CLI -i --apihost "$APIHOST" package update --auth "$AUTH"  --shared no "$NAMESPACE/routemgmt" \
-a description "This package manages the gateway API configuration." \
-p host $DB_HOST \
-p port $DB_PORT \
-p protocol $DB_PROTOCOL \
-p username $DB_USERNAME \
-p password $DB_PASSWORD \
-p dbname $DB_GWAPI \
-p gwUrl "https://api-gw.cumulus.apim.ibmcloud.com/gws/dmi/v1"


echo Installing routemgmt actions
$WSK_CLI -i --apihost "$APIHOST" action update --auth "$AUTH" --shared no "$NAMESPACE/routemgmt/createRoute" "$OPENWHISK_HOME/core/routemgmt/createRoute.js" \
-a description 'Create an API route'

$WSK_CLI -i --apihost "$APIHOST" action update --auth "$AUTH" --shared no "$NAMESPACE/routemgmt/createApi" "$OPENWHISK_HOME/core/routemgmt/createApi.js" \
-a description 'Create an API configuration'

$WSK_CLI -i --apihost "$APIHOST" action update --auth "$AUTH" --shared no "$NAMESPACE/routemgmt/updateApi" "$OPENWHISK_HOME/core/routemgmt/updateApi.js" \
-a description 'Update the specified API configuration'

$WSK_CLI -i --apihost "$APIHOST" action update --auth "$AUTH" --shared no "$NAMESPACE/routemgmt/deleteApi" "$OPENWHISK_HOME/core/routemgmt/deleteApi.js" \
-a description 'Delete the specified API configuration'

$WSK_CLI -i --apihost "$APIHOST" action update --auth "$AUTH" --shared no "$NAMESPACE/routemgmt/getApi" "$OPENWHISK_HOME/core/routemgmt/getApi.js" \
-a description 'Retrieve the specified API configuration (in JSON format)'

$WSK_CLI -i --apihost "$APIHOST" action update --auth "$AUTH" --shared no "$NAMESPACE/routemgmt/activateApi" "$OPENWHISK_HOME/core/routemgmt/activateApi.js" \
-a description 'Activate the specified API'

$WSK_CLI -i --apihost "$APIHOST" action update --auth "$AUTH" --shared no "$NAMESPACE/routemgmt/deactivateApi" "$OPENWHISK_HOME/core/routemgmt/deactivateApi.js" \
-a description 'Deactivate the specified API'

$WSK_CLI -i --apihost "$APIHOST" action update --auth "$AUTH" --shared no "$NAMESPACE/routemgmt/syncApi" "$OPENWHISK_HOME/core/routemgmt/syncApi.js" \
-a description 'Synchronize the API configuration with the API gateway'

$WSK_CLI -i --apihost "$APIHOST" action update --auth "$AUTH" --shared no "$NAMESPACE/routemgmt/getCollection" "$OPENWHISK_HOME/core/routemgmt/getCollection.js" \
-a description 'Retrieve all API routes configuration (in JSON format) for the specified collection'

$WSK_CLI -i --apihost "$APIHOST" action update --auth "$AUTH" --shared no "$NAMESPACE/routemgmt/deleteCollection" "$OPENWHISK_HOME/core/routemgmt/deleteCollection.js" \
-a description 'Delete all API routes configuration (in JSON format) for the specified collection'

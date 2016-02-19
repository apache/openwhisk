#!/bin/bash

#
# Copyright 2015-2016 IBM Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

#
# create the immortal databases in cloudant.  
# Immortal databases are NOT dropped and recreated for a new deployment,
# even for testing (at least for now)
#
# Usuage: createImmortalDbs.sh cloudant_user cloudant_password
#
USER=$1;
PASSWORD=$2;

: ${USER:?"usage: createImmortalDbs.sh cloudant_user cloudant_password"}
: ${PASSWORD:?"usage: createImmortalDbs.sh cloudant_user cloudant_password"}

SCRIPTDIR="$(cd $(dirname "$0")/ && pwd)"
source "$SCRIPTDIR/../../config/cloudantSetup.sh"

GUEST_KEY=`cat "$SCRIPTDIR/../../config/keys/auth.guest"`
WHISK_SYSTEM_KEY=`cat "$SCRIPTDIR/../../config/keys/auth.whisk.system"`

# array of immortal keys that need to be recreated in the auth table if it is ever dropped or in case of a fresh deployment
IMMORTAL_KEYS=("guest:$GUEST_KEY" "whisk.system:$WHISK_SYSTEM_KEY")

# prompt user for confirmation
echo "About to drop and recreate database '$CLOUDANT_IMMORTAL_DBS' in this cloudant account:"
echo "$USER"
echo "This will wipe the previous database if it exists and this is not reversible."
echo "Respond with 'DROPIT' to continue and anything else to abort."
read -r -p "Are you sure? " response
if [[ $response != DROPIT ]]
then
    echo "Aborted"
    exit 1
fi

CURL_ADMIN="curl --user $USER:$PASSWORD"
URL_BASE="https://$USER.cloudant.com"

for db in $CLOUDANT_IMMORTAL_DBS
do  
    echo $db

    # drop the database
    CMD="$CURL_ADMIN -X DELETE $URL_BASE/$db"
    echo $CMD
    $CMD
    
    # create the database
    CMD="$CURL_ADMIN -X PUT $URL_BASE/$db"
    echo $CMD
    $CMD
done

# recreate the "full" index on the "auth" database
$CURL_ADMIN -X POST -H 'Content-Type: application/json' \
        -d '{  
            "_id":"_design/subjects",
            "views": { "uuids": { "map": "function (doc) {\n  emit([doc.uuid], {secret: doc.key});\n}" } },
            "language":"javascript",
            "indexes": {}
         }' \
    $URL_BASE/$CLOUDANT_WHISK_AUTHS;

# recreate necessary "auth" keys
for key in "${IMMORTAL_KEYS[@]}" ; do
    SUBJECT="${key%%:*}"
    UUID="${key%:*}"
    UUID="${UUID##*:}"
    KEY="${key##*:}"
    echo Create immortal key for $SUBJECT $UUID:$KEY
    $CURL_ADMIN -X POST -H 'Content-Type: application/json' \
        -d "{
            \"_id\": \"$SUBJECT\",
            \"subject\": \"$SUBJECT\",
            \"uuid\": \"$UUID\",
            \"key\": \"$KEY\"
         }" \
    $URL_BASE/$CLOUDANT_WHISK_AUTHS;
done

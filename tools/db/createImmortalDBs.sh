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
# create the immortal databases (in Cloudant or CouchDB)
# Immortal databases are NOT dropped and recreated for a new deployment,
# even for testing (at least for now)
#
# Usage: createImmortalDbs.sh

SCRIPTDIR="$(cd $(dirname "$0")/ && pwd)"

source "$SCRIPTDIR/../../config/dbSetup.sh"
URL_BASE="$OPEN_WHISK_DB_PROTOCOL://$OPEN_WHISK_DB_HOST:$OPEN_WHISK_DB_PORT"

if [ "$OPEN_WHISK_DB_PROVIDER" == "Cloudant" ]; then
    CURL_ADMIN="curl -s --user $OPEN_WHISK_DB_USERNAME:$OPEN_WHISK_DB_PASSWORD"

    # First part of confirmation prompt.
    echo "About to drop and recreate database '$DB_IMMORTAL_DBS' in this Cloudant account:"
    echo "  $OPEN_WHISK_DB_USERNAME"

elif [ "$OPEN_WHISK_DB_PROVIDER" == "CouchDB" ]; then
    CURL_ADMIN="curl -s -k --user $OPEN_WHISK_DB_USERNAME:$OPEN_WHISK_DB_PASSWORD"

    # First part of confirmation prompt.
    echo "About to drop and recreate database '$DB_IMMORTAL_DBS' on:"
    echo "  $URL_BASE"

else
    echo "Unrecognized OPEN_WHISK_DB_PROVIDER: '$OPEN_WHISK_DB_PROVIDER'"
    exit 1
fi

GUEST_KEY=`cat "$SCRIPTDIR/../../config/keys/auth.guest"`
WHISK_SYSTEM_KEY=`cat "$SCRIPTDIR/../../config/keys/auth.whisk.system"`

# array of immortal keys that need to be recreated in the auth table if it is ever dropped or in case of a fresh deployment
IMMORTAL_KEYS=("guest:$GUEST_KEY" "whisk.system:$WHISK_SYSTEM_KEY")

# ...second part of prompt to user for confirmation:
if [[ "$1" != "--dropit" ]]; then
    echo "This will wipe the previous database if it exists and this is not reversible."
    echo "Respond with 'DROPIT' to continue and anything else to abort."
    read -r -p "Are you sure? " response
    if [[ "$response" != "DROPIT" ]]; then
        echo "Aborted"
        exit 1
    fi
fi

for db in $DB_IMMORTAL_DBS
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
    $URL_BASE/$DB_WHISK_AUTHS;

# recreate necessary "auth" keys
for key in "${IMMORTAL_KEYS[@]}" ; do
    SUBJECT="${key%%:*}"
    UUID="${key%:*}"
    UUID="${UUID##*:}"
    KEY="${key##*:}"
    echo Create immortal key for $SUBJECT
    $CURL_ADMIN -X POST -H 'Content-Type: application/json' \
        -d "{
            \"_id\": \"$SUBJECT\",
            \"subject\": \"$SUBJECT\",
            \"uuid\": \"$UUID\",
            \"key\": \"$KEY\"
         }" \
    $URL_BASE/$DB_WHISK_AUTHS;
done

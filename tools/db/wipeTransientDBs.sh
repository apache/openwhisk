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
# drop and recreate the transient whisk cloudant databases.
# NOTE: before editing this file, review the notes in
# whisk.core.entity.WhiskStore as any changes here to the views
# may require changes to the supporting query methods.
#

SCRIPTDIR="$(cd $(dirname "$0")/ && pwd)"
source "$SCRIPTDIR/common.sh"

#FIXME: ok as long as there is only one transient db
DB_TRANSIENT_DBS=$(getProperty "$PROPERTIES_FILE" "db.whisk.actions")

## drop and recreate the transient databases
for db in $DB_TRANSIENT_DBS
do
    echo "dropping database: '$db'"

    # drop the database
    CMD="$CURL_ADMIN -X DELETE $URL_BASE/$db"
    RES=$($CMD)
    if [[ "$RES" == '{"ok":true}' ]]; then
        echo DELETED
    else
        # table may not exist
        echo WARNING: $RES
    fi

    echo "recreating database '$db'"
    CMD="$CURL_ADMIN -X PUT $URL_BASE/$db"
    RES=$($CMD)
    if [[ "$RES" == '{"ok":true}' ]]; then
        echo RECREATED
    else
        echo ERROR: $RES
        exit 1
    fi
done

# recreate views required by whisk.core.entity.WhiskStore
echo "loading views"
source "$SCRIPTDIR/loadTransientDBViews.sh"

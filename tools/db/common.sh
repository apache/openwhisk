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

# Looks up a value in a property file.
# arg $1: the path to the property file.
# arg $2: the name of the property to look up
# return (print to stdout): the value of the property.
function getProperty() {
    file=$1
    name=$2
    value=$(cat "$file" | grep "^$name=" |cut -d "=" -f 2)
    echo $value
}

SCRIPTDIR="$(cd $(dirname "$0")/ && pwd)"
PROPERTIES_FILE="$SCRIPTDIR/../../whisk.properties"

DB_PROVIDER=$(getProperty "$PROPERTIES_FILE" "db.provider")
DB_PROTOCOL=$(getProperty "$PROPERTIES_FILE" "db.protocol")
DB_PREFIX=$(getProperty "$PROPERTIES_FILE" "db.prefix")
DB_HOST=$(getProperty "$PROPERTIES_FILE" "db.host")
DB_PORT=$(getProperty "$PROPERTIES_FILE" "db.port")
DB_USERNAME=$(getProperty "$PROPERTIES_FILE" "db.username")
DB_PASSWORD=$(getProperty "$PROPERTIES_FILE" "db.password")

if [ "$DB_PROVIDER" == "CouchDB" ]; then
    CURL_ADMIN="curl -s -k --user $DB_USERNAME:$DB_PASSWORD"
else
    CURL_ADMIN="curl -s --user $DB_USERNAME:$DB_PASSWORD"
fi
URL_BASE="$DB_PROTOCOL://$DB_HOST:$DB_PORT"

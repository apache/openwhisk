#!/usr/bin/env bash

#
# This script looks inside the /import folder to see if there are exported JSON documents to be imported.
# NOTE: it only creates the Database if it's not created already.
#

#2. wait for the DB to start
echo "waiting for the database to come up ... "
until $(curl --output /dev/null --silent --head --fail http://127.0.0.1:5984/_all_dbs); do printf '.'; sleep 5; done

#3. import from /import if DB is empty
echo "import databases from the /import folder"

cd /import
# rename couchdb_{DB_NAME}.json in {DB_NAME}
for f in couchdb*.json; do mv "$f" "`echo $f | cut -c 9- | cut -d'.' -f 1`"; done

# check if DB exists by marking all the DBs that are found with _exists suffix
for f in *; do mv "$f" "`if [ $(curl -s -o /dev/null -i -w "%{http_code}" http://localhost:5984/$f) -eq 200 ]; then echo ${f}_exists; else echo $f; fi`"; done
echo "DB status ..."
ls -la
echo "Removing DBs that exist ... "
rm -rf *_exists

# at this point we only have the DBs that don't exist and we can create them
echo "Fixing imports as per https://wiki.apache.org/couchdb/HTTP_Bulk_Document_API ..."
for f in *; do cat ${f} | jq '{"docs": [.rows[].doc]}' | jq 'del(.docs[]._rev)' > ${f}.tmp; mv ${f}.tmp ${f}; done
ls -la
echo "Creating DBs ..."
find ./ -type f -exec curl -X PUT -u ${COUCHDB_USER}:${COUCHDB_PASSWORD}  http://127.0.0.1:5984/{} \;
echo "Importing DBs ..."
find ./ -type f -exec curl -d @{} -u ${COUCHDB_USER}:${COUCHDB_PASSWORD} -H "Content-Type:application/json" http://127.0.0.1:5984/{}/_bulk_docs \;
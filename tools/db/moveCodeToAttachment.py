#!/usr/bin/env python
'''Python script update actions.
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
'''

import argparse
import couchdb.client
import time
from couchdb import ResourceNotFound

def updateNonJavaAction(db, doc, id):
    updated = False
    code = doc['exec']['code']

    if not isinstance(code, dict):
        db.put_attachment(doc, code, 'codefile', 'text/plain')
        doc = db.get(id)
        doc['exec']['code'] = {
            'attachmentName': 'codefile',
            'attachmentType': 'text/plain'
        }
        db.save(doc)
        updated = True

    return updated

def createNonMigratedDoc(db):
    try:
        db['_design/nonMigrated']
    except ResourceNotFound:
        db.save({
            '_id': '_design/nonMigrated',
            'language': 'javascript',
            'views': {
                'actions': {
                    'map': 'function (doc) {   var isAction = function (doc) {     return (doc.exec !== undefined)   };   var isMigrated = function (doc) {     return (doc._attachments !== undefined && doc._attachments.codefile !== undefined && typeof doc.code != \'string\')   };   if (isAction(doc) && !isMigrated(doc)) try {     emit([doc.name]);   } catch (e) {} }'
                }
            }
        })

def deleteNonMigratedDoc(db):
    del db['_design/nonMigrated']

def main(args):
    db = couchdb.client.Server(args.dbUrl)[args.dbName]
    createNonMigratedDoc(db)
    docs = db.view('_design/nonMigrated/_view/actions')
    docCount = len(docs)
    docIndex = 1

    print('Number of actions to update: {}'.format(docCount))

    for row in docs:
        id = row.id
        doc = db.get(id)

        print('Updating action {0}/{1}: "{2}"'.format(docIndex, docCount, id))

        if 'exec' in doc and 'code' in doc['exec']:
            if doc['exec']['kind'] != 'java':
                updated = updateNonJavaAction(db, doc, id)
            else:
                updated = False

            if updated:
                print('Updated action: "{0}"'.format(id))
                time.sleep(.500)
            else:
                print('Action already updated: "{0}"'.format(id))

        docIndex = docIndex + 1

    deleteNonMigratedDoc(db)

parser = argparse.ArgumentParser(description='Utility to update database action schema.')
parser.add_argument('--dbUrl', required=True, help='Server URL of the database. E.g. \"https://xxx:yyy@domain.couch.com:443\"')
parser.add_argument('--dbName', required=True, help='Name of the Database to update.')
args = parser.parse_args()

main(args)

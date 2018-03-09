#!/usr/bin/env python
"""Python script to delete whisks entries having none existent ns.

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
"""

import argparse
import time
import couchdb.client

skipWhisks = 0

try:
    long        # Python 2
except NameError:
    long = int  # Python 3

HOUR = 1000 * 60 * 60
DAY = HOUR * 24

#
# simple ring buffer like list
#
class SimpleRingBuffer:
    def __init__(self, size):
        self.index = -1
        self.data = []
        self.maxsize = size * 2

    def append(self, ns, bool):

        self.index=(self.index+2)%self.maxsize

        if len(self.data) < self.maxsize:
            self.data.append(ns)
            self.data.append(bool)
        else:
            self.data[self.index-1]=ns
            self.data[(self.index)]=bool

    def getself(self):
        return self.data

    def get(self, ns):
        if ns in self.data:
            return self.data[self.data.index(ns)+1]
        else:
            return None

#
# mark whisks entry for deletion of delete if already marked
#
def deleteWhisk(dbWhisks, wdoc):

    global skipWhisks

    wdocd = dbWhisks[wdoc['id']]
    if not 'markedForDeletion' in wdocd:
        print('marking: {0}'.format(wdoc['id']))
        dts = int(time.time() * 1000)
        wdocd['markedForDeletion'] = dts
        dbWhisks.save(wdocd)
    else:
        dts = wdocd['markedForDeletion']
        now = int(time.time() * 1000)
        elapsedh = int((now - dts) / HOUR)
        elapsedd = int((now - dts) / DAY)

        if elapsedd >= args.days:
            print('deleting: {0}'.format(wdoc['id']))
            dbWhisks.delete(wdocd)
            skipWhisks-=1
        else:
            print('marked: {0}, elapsed hours: {1}, elapsed days: {2}'.format(wdoc['id'], elapsedh, elapsedd))


#
# check subjects db for existence of ns
#
def checkNamespace(dbSubjects, namespace):

    while True:

        allNamespaces = dbSubjects.view('subjects/identities', startkey=[namespace], endkey=[namespace])

        if allNamespaces:
            return True
        else:
            return False


#
# check whisks db for entries having none existent ns
#
def checkWhisks(args):

    dbWhisks = couchdb.client.Server(args.dbUrl)[args.dbNameWhisks]
    dbSubjects = couchdb.client.Server(args.dbUrl)[args.dbNameSubjects]

    rb = SimpleRingBuffer(args.bufferLen)

    global skipWhisks
    while True:
        allWhisks = dbWhisks.view('_all_docs', limit=args.docsPerRequest, skip=skipWhisks)
        skipWhisks += args.docsPerRequest
        if allWhisks:
            for wdoc in allWhisks:
                if wdoc['id'].startswith('_design/'):
                    print('skipping: {0}'.format(wdoc['id']))
                    continue
                namespace = wdoc['id'][0:wdoc['id'].find('/')]

                exists = rb.get(namespace)
                if exists == None:
                    exists = checkNamespace(dbSubjects, namespace)
                    rb.append(namespace, exists)

                if exists:
                    print('keeping: {0}'.format(wdoc['id']))
                else:
                    deleteWhisk(dbWhisks, wdoc)
        else:
            return


parser = argparse.ArgumentParser(description="Utility to mark/delete whisks entries where the ns does not exist in the subjects database.")
parser.add_argument("--dbUrl", required=True, help="Server URL of the database, that has to be cleaned of old activations. E.g. 'https://xxx:yyy@domain.couch.com:443'")
parser.add_argument("--dbNameWhisks", required=True, help="Name of the Whisks Database of the whisks entries to be marked for deletion or deleted if already marked.")
parser.add_argument("--dbNameSubjects", required=True, help="Name of the Subjects Database.")
parser.add_argument("--days", required=True, type=int, default=7, help="How many days whisks keep entries marked for deletion before deleting them.")
parser.add_argument("--docsPerRequest", type=int, default=200, help="Number of documents handled on each CouchDb Request. Default is 200.")
parser.add_argument("--bufferLen", type=int, default=100, help="Maximum buffer length to cache already checked ns. Default is 100.")
args = parser.parse_args()

checkWhisks(args)

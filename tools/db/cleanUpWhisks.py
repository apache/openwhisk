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
import sys

skipWhisks = 0
marking = marked = deleting = skipping = keeping = 0

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
        self.index = 0
        self.data = []
        self.maxsize = size

    def append(self, ns, exists):
        if len(self.data) < self.maxsize:
            self.data.append((ns,exists))
        else:
            self.data[self.index]=(ns,exists)
        self.index=(self.index+1)%self.maxsize

    def getself(self):
        return self.data

    def get(self, ns):
        if (ns,True) in self.data:
            return True
        elif (ns,False) in self.data:
            return False
        else:
            return None

#
# mark whisks entry for deletion of delete if already marked
#
def deleteWhisk(dbWhisks, wdoc, days):

    global skipWhisks, marking, marked, deleting

    wdocd = dbWhisks[wdoc['id']]
    if not 'markedForDeletion' in wdocd:
        print('marking: {0}'.format(wdoc['id']))
        dts = int(time.time() * 1000)
        wdocd['markedForDeletion'] = dts
        dbWhisks.save(wdocd)
        marking+=1
    else:
        dts = wdocd['markedForDeletion']
        now = int(time.time() * 1000)
        elapsedh = int((now - dts) / HOUR)
        elapsedd = int((now - dts) / DAY)

        if elapsedd >= days:
            print('deleting: {0}'.format(wdoc['id']))
            dbWhisks.delete(wdocd)
            skipWhisks-=1
            deleting+=1
        else:
            print('marked: {0}, elapsed hours: {1}, elapsed days: {2}'.format(wdoc['id'], elapsedh, elapsedd))
            marked+=1


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
# update last namespace info and print number of kept documents
#
def updateLastNamespaceInfo(namespace, lastNamespaceInfo):

    if namespace != lastNamespaceInfo[0]:
        if lastNamespaceInfo[0] != None:
            print('keeping: {0} doc(s) for namespace {1}'.format(lastNamespaceInfo[1], lastNamespaceInfo[0]))
        lastNamespaceInfo = (namespace,0)
    lastNamespaceInfo = (namespace, lastNamespaceInfo[1]+1)
    return lastNamespaceInfo


#
# get delimiters for namespace retrieval by database type
#
def getDelimiters(whiskDatabaseType):

    if whiskDatabaseType == "whisks":
        return None, '/'
    elif whiskDatabaseType == "cloudanttrigger":
        return ':', ':'
    elif whiskDatabaseType == "kafkatrigger":
        return '/', '/'
    elif whiskDatabaseType == "alarmservice":
        return '/', '/'
    else:
        print('{0}: error: {1} is not supported for --whiskDBType'.format(sys.argv[0], whiskDatabaseType))
        exit(1)


#
# check whisks db for entries having none existent ns
#
def checkWhisks(args):

    delimiter1, delimiter2 = getDelimiters(args.whiskDBType)

    dbWhisks = couchdb.client.Server(args.dbUrl)[args.dbNameWhisks]
    dbSubjects = couchdb.client.Server(args.dbUrl)[args.dbNameSubjects]

    rb = SimpleRingBuffer(args.bufferLen)

    global skipWhisks
    global skipWhisks, skipping, keeping, marking, marked, deleting
    lastNamespaceInfo = (None, 0)
    while True:
        allWhisks = dbWhisks.view('_all_docs', limit=args.docsPerRequest, skip=skipWhisks)
        skipWhisks += args.docsPerRequest
        if allWhisks:
            for wdoc in allWhisks:
                if wdoc['id'].startswith('_design/'):
                    skipping += 1
                    print('skipping: {0}'.format(wdoc['id']))
                    continue

                docID = wdoc['id']
                index = 0

                if delimiter1 != None:
                    index = docID.find(delimiter1)+1
                namespace = docID[index:docID.find(delimiter2, index)]

                lastNamespaceInfo = updateLastNamespaceInfo(namespace, lastNamespaceInfo)

                exists = rb.get(namespace)
                if exists == None:
                    exists = checkNamespace(dbSubjects, namespace)
                    rb.append(namespace, exists)

                if not exists:
                    deleteWhisk(dbWhisks, wdoc, args.days)
                    lastNamespaceInfo = (None, 0)
                else:
                    keeping += 1

        else:
            break

    # print final statistic
    updateLastNamespaceInfo(None, lastNamespaceInfo)
    print('statistic: skipped({0}), kept({1}), marked for deletion({2}), already marked({3}), deleted({4})'.format(skipping, keeping, marking, marked, deleting))


parser = argparse.ArgumentParser(description="Utility to mark/delete whisks entries where the ns does not exist in the subjects database.")
parser.add_argument("--dbUrl", required=True, help="Server URL of the database, that has to be cleaned of old activations. E.g. 'https://xxx:yyy@domain.couch.com:443'")
parser.add_argument("--dbNameWhisks", required=True, help="Name of the Whisks Database of the whisks entries to be marked for deletion or deleted if already marked.")
parser.add_argument("--dbNameSubjects", required=True, help="Name of the Subjects Database.")
parser.add_argument("--whiskDBType", required=True, help="Type of the Whisks Database. Supported are whisks, cloudanttrigger, kafkatrigger, alarmservice")
parser.add_argument("--days", required=True, type=int, default=7, help="How many days whisks keep entries marked for deletion before deleting them.")
parser.add_argument("--docsPerRequest", type=int, default=200, help="Number of documents handled on each CouchDb Request. Default is 100.")
parser.add_argument("--bufferLen", type=int, default=100, help="Maximum buffer length to cache already checked ns. Default is 100.")
args = parser.parse_args()
checkWhisks(args)

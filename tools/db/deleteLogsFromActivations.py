#!/usr/bin/env python
"""Python script to delete logs from old Activations.

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

try:
    long        # Python 2
except NameError:
    long = int  # Python 3

DAY = 1000 * 60 * 60 * 24

def removeLogFromActivation(viewResult):
    doc = viewResult.doc
    doc["logs"] = []
    return doc

#
# Delete activations
#
def deleteLogsFromOldActivations(args):
    db = couchdb.client.Server(args.dbUrl)[args.dbName]
    endkey = long(time.time() * 1000) - args.days * DAY
    while True:
        activations = db.view("logCleanup/byDateWithLogs", limit=args.docsPerRequest, start_key=0, end_key=endkey, include_docs=True)
        if activations:
            activationsWithoutLogs = [removeLogFromActivation(activation) for activation in activations]
            db.update(activationsWithoutLogs)
        else:
            return

parser = argparse.ArgumentParser(description="Utility to delete logs from activations that are older than x days in given database.")
parser.add_argument("--dbUrl", required=True, help="Server URL of the database, that has to be cleaned of old activations. E.g. 'https://xxx:yyy@domain.couch.com:443'")
parser.add_argument("--dbName", required=True, help="Name of the Database of the activations to be truncated.")
parser.add_argument("--days", required=True, type=int, help="How many days of the logs in activations to be kept.")
parser.add_argument("--docsPerRequest", type=int, default=20, help="Number of documents handled on each CouchDb Request. Default is 20.")
args = parser.parse_args()

deleteLogsFromOldActivations(args)

#!/usr/bin/env python
"""
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
import couchdb.client
import requests
from urllib import quote_plus


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description="Utility to migrate actions with new ids.")
    parser.add_argument("--dbUrl", required=True, help="Server URL of the database. E.g. 'https://xxx:yyy@domain.couch.com:443'")
    parser.add_argument("--dbName", required=True, help="Name of the Database of the actions to be migration.")
    parser.add_argument("--docsPerRequest", type=int, default=200, help="Number of documents handled on each CouchDb Request. Default is 200.")

    args = parser.parse_args()

    db = couchdb.client.Server(args.dbUrl)[args.dbName]
    start = 0
    actions = db.view("whisks.v2.1.0/actions", skip=start, limit=args.docsPerRequest, reduce=False)

    while len(actions) != 0:
        for action in actions:
            actionName = "%s/%s" % (action.value["namespace"], action.value["name"])
            newId = "%s@%s" % (actionName, action.value["version"])
            # this action is using old style id, copy it with new id which append `@version` to it
            if(action.id != newId):
                print("Copy %s to %s:...........\n" % (action.id, newId))
                url = "%s/%s/%s" % (args.dbUrl, args.dbName, quote_plus(actionName))
                headers = {"Content-Type": "application/json", "Destination": newId}
                res = requests.request("COPY", url, headers = headers)
                print("Copying result is %s\n" % res.content)
            else:
                print("Action %s is already using new style id, skip it\n" % action.id)

        start = start + args.docsPerRequest
        actions = db.view("whisks.v2.1.0/actions", skip=start, limit=args.docsPerRequest, reduce=False)

#!/usr/bin/env python
"""Python script to replicate and replay databases.

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
import re
import couchdb.client
import functools

def retry(fn, retries):
    try:
        return fn()
    except:
        if (retries > 0):
            time.sleep(1)
            return retry(fn, retries - 1)
        else:
            raise


def replicateDatabases(args):
    """Replicate databases."""
    sourceDb = couchdb.client.Server(args.sourceDbUrl)
    targetDb = couchdb.client.Server(args.targetDbUrl)

    excludedDatabases = args.exclude.split(",")
    excludedBaseNames = [x for x in args.excludeBaseName.split(",") if x != ""]

    # Create _replicator DB if it does not exist yet.
    if "_replicator" not in sourceDb:
        sourceDb.create("_replicator")

    replicator = sourceDb["_replicator"]

    now = int(time.time())
    backupPrefix = "backup_%d_" % now

    def isExcluded(dbName):
        dbNameWithoutPrefix = dbName.replace(args.dbPrefix, "", 1)
        # is the databaseName is in the list of excluded database
        isNameExcluded = dbNameWithoutPrefix in excludedDatabases
        # if one of the basenames matches, the database is excluded
        isBaseNameExcluded = functools.reduce(lambda x, y: x or y, [dbNameWithoutPrefix.startswith(en) for en in excludedBaseNames], False)
        return isNameExcluded or isBaseNameExcluded

    # Create backup of all databases with given prefix
    print("----- Create backups -----")
    for db in [dbName for dbName in sourceDb if dbName.startswith(args.dbPrefix) and not isExcluded(dbName)]:
        backupDb = backupPrefix + db if not args.continuous else 'continuous_' + db
        replicateDesignDocument = {
            "_id": backupDb,
            "source": args.sourceDbUrl + "/" + db,
            "target": args.targetDbUrl + "/" + backupDb,
            "create_target": True,
            "continuous": args.continuous,
        }
        print("create backup: %s" % backupDb)

        filterName = "snapshotFilters"
        filterDesignDocument = sourceDb[db].get("_design/%s" % filterName)
        if not args.continuous and filterDesignDocument:
            replicateDesignDocument["filter"] = "%s/withoutDeletedAndDesignDocuments" % filterName
        replicator.save(replicateDesignDocument)

    def isBackupDb(dbName):
        return re.match("^backup_\d+_" + args.dbPrefix, dbName)

    def extractTimestamp(dbName):
        return int(dbName.split("_")[1])

    def isExpired(timestamp):
        return now - args.expires > timestamp

    # Delete all documents in the _replicator-database of old backups to avoid that they continue after they are deprecated
    print("----- Delete backup-documents older than %d seconds -----" % args.expires)
    for doc in [doc for doc in replicator.view('_all_docs', include_docs=True) if isBackupDb(doc.id) and isExpired(extractTimestamp(doc.id))]:
        print("deleting backup document: %s" % doc.id)
        # Get again the latest version of the document to delete the right revision and avoid Conflicts
        retry(lambda: replicator.delete(replicator[doc.id]), 5)

    # Delete all backup-databases, that are older than specified
    print("----- Delete backups older than %d seconds -----" % args.expires)
    for db in [db for db in targetDb if isBackupDb(db) and isExpired(extractTimestamp(db))]:
        print("deleting backup: %s" % db)
        targetDb.delete(db)


def replayDatabases(args):
    """Replays databases."""
    sourceDb = couchdb.client.Server(args.sourceDbUrl)

    # Create _replicator DB if it does not exist yet.
    if "_replicator" not in sourceDb:
        sourceDb.create("_replicator")

    for db in [dbName for dbName in sourceDb if dbName.startswith(args.dbPrefix)]:
        plainDbName = db.replace(args.dbPrefix, "")
        (identifier, _) = sourceDb["_replicator"].save({
            "source": args.sourceDbUrl + "/" + db,
            "target": args.targetDbUrl + "/" + plainDbName,
            "create_target": True
        })
        print("replaying backup: %s -> %s (%s)" % (db, plainDbName, identifier))

parser = argparse.ArgumentParser(description="Utility to create a backup of all databases with the defined prefix.")
parser.add_argument("--sourceDbUrl", required=True, help="Server URL of the source database, that has to be backed up. E.g. 'https://xxx:yyy@domain.couch.com:443'")
parser.add_argument("--targetDbUrl", required=True, help="Server URL of the target database, where the backup is stored. Like sourceDbUrl.")
subparsers = parser.add_subparsers(help='sub-command help')

# Replicate
replicateParser = subparsers.add_parser("replicate", help="Replicates source databases to the target database.")
replicateParser.add_argument("--dbPrefix", required=True, help="Prefix of the databases, that should be backed up.")
replicateParser.add_argument("--expires", required=True, type=int, help="Deletes all backups, that are older than the given value in seconds.")
replicateParser.add_argument("--continuous", action="store_true", help="Wether or not the backup should be continuous")
replicateParser.add_argument("--exclude", default="", help="Comma separated list of database names, that should not be backed up. (Without prefix).")
replicateParser.add_argument("--excludeBaseName", default="", help="Comma separated list of database base names. All databases, that have this basename in their name will not be backed up. (Without prefix).")
replicateParser.set_defaults(func=replicateDatabases)

# Replay
replicateParser = subparsers.add_parser("replay", help="Replays source databases to the target database.")
replicateParser.add_argument("--dbPrefix", required=True, help="Prefix of the databases, that should be replayed. Usually 'backup_{TIMESTAMP}_'")
replicateParser.set_defaults(func=replayDatabases)

arguments = parser.parse_args()
arguments.func(arguments)

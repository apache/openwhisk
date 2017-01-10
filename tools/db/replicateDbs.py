#!/usr/bin/env python

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

import argparse
import time
import re
import couchdb.client

#
# Replicates databases
#
def replicateDatabases(args):
    sourceDb = couchdb.client.Server(args.sourceDbUrl)
    targetDb = couchdb.client.Server(args.targetDbUrl)

    # Create _replicator DB if it does not exist yet.
    if "_replicator" not in sourceDb:
        sourceDb.create("_replicator")

    now = int(time.time())
    backupPrefix = "backup_%d_" % now

    # Create backup of all databases with given prefix
    print "----- Create backups -----"
    for db in filter(lambda dbName: dbName.startswith(args.dbPrefix), sourceDb):
        backupDb = backupPrefix + db if not args.continuous else 'continuous_' + db
        print "create backup: %s" % backupDb
        sourceDb["_replicator"].save({
            "_id": backupDb,
            "source": args.sourceDbUrl + "/" + db,
            "target": args.targetDbUrl + "/" + backupDb,
            "create_target": True,
            "continuous": args.continuous
        })

    def isBackupDb(dbName): return re.match("backup_\d+_", dbName)
    def extractTimestamp(dbName): return int(dbName.split("_")[1])
    def isExpired(timestamp): return now - args.expires > timestamp

    # Delete all backup-databases, that are older than specified
    print "----- Delete backups older than %d seconds -----" % args.expires
    for db in filter(lambda db: isBackupDb(db) and isExpired(extractTimestamp(db)), targetDb):
        print "deleting backup: %s" % db
        targetDb.delete(db)

#
# Replays databases
#
def replayDatabases(args):
    sourceDb = couchdb.client.Server(args.sourceDbUrl)

    # Create _replicator DB if it does not exist yet.
    if "_replicator" not in sourceDb:
        sourceDb.create("_replicator")

    for db in filter(lambda dbName: dbName.startswith(args.dbPrefix), sourceDb):
        plainDbName = db.replace(args.dbPrefix, "")
        (identifier, _) = sourceDb["_replicator"].save({
            "source": args.sourceDbUrl + "/" + db,
            "target": args.targetDbUrl + "/" + plainDbName,
            "create_target": True
        })
        print "replaying backup: %s -> %s (%s)" % (db, plainDbName, identifier)

parser = argparse.ArgumentParser(description="Utility to create a backup of all databases with the defined prefix.")
parser.add_argument("--sourceDbUrl", required=True, help="Server URL of the source database, that has to be backed up. E.g. 'https://xxx:yyy@domain.couch.com:443'")
parser.add_argument("--targetDbUrl", required=True, help="Server URL of the target database, where the backup is stored. Like sourceDbUrl.")
subparsers = parser.add_subparsers(help='sub-command help')

# Replicate
replicateParser = subparsers.add_parser("replicate", help="Replicates source databases to the target database.")
replicateParser.add_argument("--dbPrefix", required=True, help="Prefix of the databases, that should be backed up.")
replicateParser.add_argument("--expires", required=True, type=int, help="Deletes all backups, that are older than the given value in seconds.")
replicateParser.add_argument("--continuous", action="store_true", help="Wether or not the backup should be continuous")
replicateParser.set_defaults(func=replicateDatabases)

# Replay
replicateParser = subparsers.add_parser("replay", help="Replays source databases to the target database.")
replicateParser.add_argument("--dbPrefix", required=True, help="Prefix of the databases, that should be replayed. Usually 'backup_{TIMESTAMP}_'")
replicateParser.set_defaults(func=replayDatabases)

arguments = parser.parse_args()
arguments.func(arguments)

#!/usr/bin/env python

#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

from collections import namedtuple
import glob
import sys
import os
import argparse
import traceback
import pydocumentdb.documents as documents
import pydocumentdb.errors as document_errors
import pydocumentdb.document_client as document_client

try:
    import argcomplete
except ImportError:
    argcomplete = False

CLI_DIR = os.path.dirname(os.path.realpath(sys.argv[0]))
# ROOT_DIR is the OpenWhisk repository root
ROOT_DIR = os.path.join(os.path.join(CLI_DIR, os.pardir), os.pardir)

DbContext = namedtuple('DbContext', ['client', 'db', 'whisks', 'subjects', 'activations'])
verbose = False


def main():
    global verbose
    exit_code = 0
    try:
        args = parse_args()
        verbose = args.verbose
        client = init_client(args)
        exit_code = {
            'init': init_cmd,
            'prune': prune_cmd,
            'drop': drop_cmd
        }[args.cmd](args, client)
    except Exception as e:
        print('Exception: ', e)
        traceback.print_exc()
        exit_code = 1

    sys.exit(exit_code)


def parse_args():
    parser = argparse.ArgumentParser(description='OpenWhisk CosmosDB bootstrap tool')
    parser.add_argument('--endpoint', help='DB Endpoint url like https://example.documents.azure.com:443/',
                        required=True)
    parser.add_argument('--key', help='DB access key', required=True)
    parser.add_argument('-v', '--verbose', help='Verbose mode', action="store_true")

    subparsers = parser.add_subparsers(title='available commands', dest='cmd')

    propmenu = subparsers.add_parser('init', help='initialize database')
    propmenu.add_argument('db', help='Database name under which the collections would be created')
    propmenu.add_argument('--dir', help='Directory under which auth files are stored')

    propmenu = subparsers.add_parser('prune', help='remove stale databases created by test')
    propmenu.add_argument('--prefix', help='Database name prefix which are matched for removal', default="travis-")

    propmenu = subparsers.add_parser('drop', help='drop database')
    propmenu.add_argument('db', help='Database name to be removed')

    if argcomplete:
        argcomplete.autocomplete(parser)
    return parser.parse_args()


def init_cmd(args, client):
    db = get_or_create_db(client, args.db)

    whisks = init_coll(client, db, "whisks")
    subjects = init_coll(client, db, "subjects")
    activations = init_coll(client, db, "activations")

    db_ctx = DbContext(client, db, whisks, subjects, activations)
    init_auth(db_ctx)
    return 0


def prune_cmd(args, client):
    # Remove database which are one day old
    pass


def drop_cmd(args, client):
    db = get_db(client, args.db)
    if db is not None:
        client.DeleteDatabase(db['_self'])
        log("Removed database : %s" % args.db)
    else:
        log("Database %s not found" % args.db)


def init_auth(ctx):
    for subject in find_default_subjects():
        link = create_link(ctx.db, ctx.subjects, subject['id'])
        options = {'partitionKey': subject.get('id')}
        try:
            ctx.client.ReadDocument(link, options)
            log('Subject already exists : ' + subject['id'])
        except document_errors.HTTPFailure as e:
            if e.status_code == 404:
                ctx.client.CreateDocument(ctx.subjects['_self'], subject, options)
                log('Created subject : ' + subject['id'])
            else:
                raise e


def create_link(db, coll, doc_id):
    return 'dbs/' + db['id'] + '/colls/' + coll['id'] + '/docs/' + doc_id


def find_default_subjects():
    files_dir = os.path.join(ROOT_DIR, "ansible/files")
    for name in glob.glob1(files_dir, "auth.*"):
        auth_file = open(os.path.join(files_dir, name), 'r')
        uuid, key = auth_file.read().strip().split(":")
        subject = name[name.index('.') + 1:]
        doc = {
            'id': subject,
            'subject': subject,
            'namespaces': [
                {
                    'name': subject,
                    'uuid': uuid,
                    'key': key
                }
            ]
        }
        auth_file.close()
        yield doc


def init_client(args):
    return document_client.DocumentClient(args.endpoint, {'masterKey': args.key})


def get_db(client, db_name):
    query = client.QueryDatabases('SELECT * FROM root r WHERE r.id=\'' + db_name + '\'')
    return next(iter(query), None)


def get_or_create_db(client, db_name):
    db = get_db(client, db_name)
    if db is None:
        db = client.CreateDatabase({'id': db_name})
        log('Created database "%s"' % db_name)
    return db


def init_coll(client, db, coll_name):
    query = client.QueryCollections(db['_self'], 'SELECT * FROM root r WHERE r.id=\'' + coll_name + '\'')
    it = iter(query)
    coll = next(it, None)
    if coll is None:
        collection_definition = {'id': coll_name,
                                 'partitionKey':
                                     {
                                         'paths': ['/id'],
                                         'kind': documents.PartitionKind.Hash
                                     }
                                 }
        collection_options = {}  # {'offerThroughput': 10100}
        coll = client.CreateCollection(db['_self'], collection_definition, collection_options)
        log('Created collection "%s"' % coll_name)
    return coll


def log(msg):
    if verbose:
        print(msg)


if __name__ == '__main__':
    main()

#!/usr/bin/python

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

from __future__ import absolute_import, division, print_function
__metaclass__ = type


DOCUMENTATION = '''
---
module: mongodb
short_description:  A module which support some simple operations on MongoDB.
description:
    - Including add user/insert document/create indexes in MongoDB
options:
    login_user:
        description:
            - The username used to authenticate with
        required: true
        default: null
    login_password:
        description:
            - The password used to authenticate with
        required: true
        default: null
    login_host:
        description:
            - The host running the database
        required: false
        default: localhost
    login_port:
        description:
            - The port to connect to
        required: false
        default: 27017
    login_database:
        description:
            - The database where login credentials are stored
        required: true
        default: null
    replica_set:
        description:
            - Replica set to connect to (automatically connects to primary for writes)
        required: false
        default: null
    database:
        description:
            - The name of the database you want to manipulate
        required: true
    ssl:
        description:
            - Whether to use an SSL connection when connecting to the database
        default: False
    ssl_cert_reqs:
        description:
            - Specifies whether a certificate is required from the other side of the connection, and whether it will be validated if provided.
        required: false
        default: "CERT_REQUIRED"
        choices: ["CERT_REQUIRED", "CERT_OPTIONAL", "CERT_NONE"]
    user:
        description:
            - The name of the user to add or remove, required when use 'user' mode
        required: false
        default: null
    password:
        description:
            - The password to use for the user, required when use 'user' mode
        required: false
        default: null
    roles:
        description:
            - The roles of the user, it's a list of dict, each dict requires two fields: 'db' and 'role', required when use 'user' mode
        required: false
        default: null
    collection:
        required: false
        description:
            - The name of the collection you want to manipulate, required when use 'doc' or 'indexes' mode
    doc:
        required: false
        description:
            - The document you want to insert into MongoDB, required when use 'doc' mode
    indexes:
        required: false
        description:
            - The indexes you want to create in MongoDB, it's a list of dict, you can see the example for the usage, required when use 'index' mode
    force_update:
        required: false
        description:
            - Whether replace/update existing user or doc or raise DuplicateKeyError, default is false
    mode:
        required: false
        default: user
        choices: ['user', 'doc', 'index']
        description:
            - use 'user' mode if you want to add user, 'doc' mode to insert document, 'index' mode to create indexes

requirements: [ "pymongo" ]
author:
    - "Jinag PengCheng"
'''

EXAMPLES = '''
# add user
- mongodb:
    login_user: root
    login_password: root_password
    login_database: admin
    database: admin
    user: test
    password: 123456
    roles:
      - db: test_database
        role: read
    force_update: true

# add doc
- mongodb:
    login_user: root
    login_password: root_password
    login_database: admin
    mode: doc
    database: admin
    collection: main
    doc:
      id: "id/document"
      title: "the name of document"
      content: "which doesn't matter"
    force_update: true

# add indexes
- mongodb:
    login_user: root
    login_password: root_password
    login_database: admin
    mode: index
    database: admin
    collection: main
    indexes:
      - index:
        - field: updated_at
          direction: 1
        - field: name
          direction: -1
        name: test-index
        unique: true
'''

import os
import ssl as ssl_lib
import traceback

from ansible.module_utils.basic import AnsibleModule
from ansible.module_utils._text import to_native

try:
    from pymongo import ASCENDING, DESCENDING, GEO2D, GEOHAYSTACK, GEOSPHERE, HASHED, TEXT
    from pymongo import IndexModel
    from pymongo import MongoClient
    from pymongo.errors import DuplicateKeyError
except ImportError:
    pass


# =========================================
# MongoDB module specific support methods.
#

class UnknownIndexPlugin(Exception):
    pass


def check_params(params, mode, module):
    missed_params = []
    for key in OPERATIONS[mode]['required']:
        if params[key] is None:
            missed_params.append(key)

    if missed_params:
        module.fail_json(msg="missing required arguments: %s" % (",".join(missed_params)))


def _recreate_user(module, db, user, password, roles):
    try:
        db.command("dropUser", user)
        db.command("createUser", user, pwd=password, roles=roles)
    except Exception as e:
        module.fail_json(msg='Unable to create user: %s' % to_native(e), exception=traceback.format_exc())


def user(module, client, db_name, **kwargs):
    roles = kwargs['roles']
    if roles is None:
        roles = []
    db = client[db_name]

    try:
        db.command("createUser", kwargs['user'], pwd=kwargs['password'], roles=roles)
    except DuplicateKeyError as e:
        if kwargs['force_update']:
            _recreate_user(module, db, kwargs['user'], kwargs['password'], roles)
        else:
            module.fail_json(msg='Unable to create user: %s' % to_native(e), exception=traceback.format_exc())
    except Exception as e:
        module.fail_json(msg='Unable to create user: %s' % to_native(e), exception=traceback.format_exc())

    module.exit_json(changed=True, user=kwargs['user'])


def doc(module, client, db_name, **kwargs):
    coll = client[db_name][kwargs['collection']]
    try:
        coll.insert_one(kwargs['doc'])
    except DuplicateKeyError as e:
        if kwargs['force_update']:
            try:
                coll.replace_one({'_id': kwargs['doc']['_id']}, kwargs['doc'])
            except Exception as e:
                module.fail_json(msg='Unable to insert doc: %s' % to_native(e), exception=traceback.format_exc())
        else:
            module.fail_json(msg='Unable to insert doc: %s' % to_native(e), exception=traceback.format_exc())
    except Exception as e:
        module.fail_json(msg='Unable to insert doc: %s' % to_native(e), exception=traceback.format_exc())

    kwargs['doc']['_id'] = str(kwargs['doc']['_id'])
    module.exit_json(changed=True, doc=kwargs['doc'])


def _clean_index_direction(direction):
    if direction in ["1", "-1"]:
        direction = int(direction)

    if direction not in [ASCENDING, DESCENDING, GEO2D, GEOHAYSTACK, GEOSPHERE, HASHED, TEXT]:
        raise UnknownIndexPlugin("Unable to create indexes: Unknown index plugin: %s" % direction)
    return direction


def _clean_index_options(options):
    res = {}
    supported_options = set(['name', 'unique', 'background', 'sparse', 'bucketSize', 'min', 'max', 'expireAfterSeconds'])
    for key in set(options.keys()).intersection(supported_options):
        res[key] = options[key]
        if key in ['min', 'max', 'bucketSize', 'expireAfterSeconds']:
            res[key] = int(res[key])

    return res


def parse_indexes(idx):
    keys = [(k['field'], _clean_index_direction(k['direction'])) for k in idx.pop('index')]
    options = _clean_index_options(idx)
    return IndexModel(keys, **options)


def index(module, client, db_name, **kwargs):
    parsed_indexes = map(parse_indexes, kwargs['indexes'])
    try:
        coll = client[db_name][kwargs['collection']]
        coll.create_indexes(parsed_indexes)
    except Exception as e:
        module.fail_json(msg='Unable to create indexes: %s' % to_native(e), exception=traceback.format_exc())

    module.exit_json(changed=True, indexes=kwargs['indexes'])


OPERATIONS = {
    'user': { 'function': user, 'params': ['user', 'password', 'roles', 'force_update'], 'required': ['user', 'password']},
    'doc': {'function': doc, 'params': ['doc', 'collection', 'force_update'], 'required': ['doc', 'collection']},
    'index': {'function': index, 'params': ['indexes', 'collection'], 'required': ['indexes', 'collection']}
}


# =========================================
# Module execution.
#

def main():
    module = AnsibleModule(
        argument_spec=dict(
            login_user=dict(required=True),
            login_password=dict(required=True, no_log=True),
            login_host=dict(default='localhost'),
            login_port=dict(default='27017'),
            login_database=dict(required=True),
            replica_set=dict(default=None),
            database=dict(required=True, aliases=['db']),
            ssl=dict(default=False, type='bool'),
            ssl_cert_reqs=dict(default='CERT_REQUIRED', choices=['CERT_NONE', 'CERT_OPTIONAL', 'CERT_REQUIRED']),
            mode=dict(default='user', choices=['user', 'doc', 'index']),
            user=dict(default=None),
            password=dict(default=None, no_log=True),
            roles=dict(default=None, type='list'),
            collection=dict(default=None),
            doc=dict(default=None, type='dict'),
            force_update=dict(default=False, type='bool'),
            indexes=dict(default=None, type='list'),
        )
    )

    login_user = module.params['login_user']
    login_password = module.params['login_password']
    login_host = module.params['login_host']
    login_port = module.params['login_port']
    login_database = module.params['login_database']
    ssl = module.params['ssl']
    replica_set = module.params['replica_set']

    mode = module.params['mode']

    db_name = module.params['database']

    params = {key: module.params[key] for key in OPERATIONS[mode]['params']}
    check_params(params, mode, module)

    try:
        connection_params = {
            "host": login_host,
            "port": int(login_port),
            "username": login_user,
            "password": login_password,
            "authSource": login_database
        }

        if replica_set:
            connection_params["replicaset"] = replica_set

        if ssl:
            connection_params["ssl"] = ssl
            connection_params["ssl_cert_reqs"] = getattr(ssl_lib, module.params['ssl_cert_reqs'])

        client = MongoClient(**connection_params)
    except NameError:
        module.fail_json(msg='the python pymongo module is required')
    except Exception as e:
        module.fail_json(msg='unable to connect to database: %s' % to_native(e), exception=traceback.format_exc())

    OPERATIONS[mode]['function'](module, client, db_name, **params)


if __name__ == '__main__':
    main()

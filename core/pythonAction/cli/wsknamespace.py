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

import httplib
import urllib
import json
from wskaction import Action
from wsktrigger import Trigger
from wskrule import Rule
from wskpackage import Package
from wskutil import (addAuthenticatedCommand, apiBase, bold, parseQName,
                     request, responseError)


#
# 'wsk namespaces' CLI
#
class Namespace:

    def getCommands(self, parser, props):
        commands = parser.add_parser('namespace', help='work with namespaces')

        subcmds = commands.add_subparsers(title='available commands',
                                          dest='subcmd')

        subcmd = subcmds.add_parser('list', help='list available namespaces')
        addAuthenticatedCommand(subcmd, props)

        subcmd = subcmds.add_parser('get', help='get entities in namespace')
        subcmd.add_argument('name', nargs='?', help='the namespace to list')
        addAuthenticatedCommand(subcmd, props)

    def cmd(self, args, props):
        if args.subcmd == 'list':
            return self.listNamespaces(args, props)
        elif args.subcmd == 'get':
            return self.listEntitiesInNamespace(args, props)

    def listNamespaces(self, args, props):
        url = '%(apibase)s/namespaces' % {'apibase': apiBase(props)}
        res = request('GET', url, auth=args.auth, verbose=args.verbose)

        if res.status == httplib.OK:
            result = json.loads(res.read())
            print(bold('namespaces'))
            for n in result:
                print('{:<25}'.format(n))
            return 0
        else:
            return responseError(res)

    def listEntitiesInNamespace(self, args, props):
        namespace, _ = parseQName(args.name, props)
        url = ('%(apibase)s/namespaces/%(namespace)s' %
               {'apibase': apiBase(props),
                'namespace': urllib.quote(namespace)})
        res = request('GET', url, auth=args.auth, verbose=args.verbose)

        if res.status == httplib.OK:
            result = json.loads(res.read())
            print('entities in namespace: %s' %
                  bold(namespace if namespace != '_' else 'default'))
            self.printCollection(result, 'packages')
            self.printCollection(result, 'actions')
            self.printCollection(result, 'triggers')
            self.printCollection(result, 'rules')
            return 0
        else:
            return responseError(res)

    def printCollection(self, result, collection):
        if collection in result:
            print(bold(collection))
            for e in result[collection]:
                if collection == 'actions':
                    print(Action().formatListEntity(e))
                elif collection == 'triggers':
                    print(Trigger().formatListEntity(e))
                elif collection == 'rules':
                    print(Rule().formatListEntity(e))
                elif collection == 'packages':
                    print(Package().formatListEntity(e))

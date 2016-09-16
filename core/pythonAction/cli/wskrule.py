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

import json
import httplib
from wskitem import Item
from wskutil import addAuthenticatedCommand, apiBase, parseQName, request, responseError
import urllib

class Rule(Item):

    def __init__(self):
        super(Rule, self).__init__('rule', 'rules')

    def getItemSpecificCommands(self, parser, props):
        subcmd = parser.add_parser('create', help='create new rule')
        subcmd.add_argument('name', help='the name of the rule')
        subcmd.add_argument('trigger', help='the trigger')
        subcmd.add_argument('action', help='the action')
        addAuthenticatedCommand(subcmd, props)
        subcmd.add_argument('--shared', nargs='?', const='yes', choices=['yes', 'no'], help='shared action (default: private)')
        subcmd.add_argument('--enable', help='enable rule after creating it', action='store_true', default=False)

        subcmd = parser.add_parser('delete', help='delete %s' % self.name)
        subcmd.add_argument('name', help='the name of the %s' % self.name)
        addAuthenticatedCommand(subcmd, props)
        subcmd.add_argument('--disable', help='automatically disable rule before deleting it', action='store_true', default=False)

        subcmd = parser.add_parser('update', help='update an existing rule')
        subcmd.add_argument('name', help='the name of the rule')
        subcmd.add_argument('trigger', help='the trigger')
        subcmd.add_argument('action', help='the action')
        addAuthenticatedCommand(subcmd, props)
        subcmd.add_argument('--shared', nargs='?', const='yes', choices=['yes', 'no'], help='shared action (default: private)')

        subcmd = parser.add_parser('enable', help='enable rule')
        subcmd.add_argument('name', help='the name of the rule')
        addAuthenticatedCommand(subcmd, props)

        subcmd = parser.add_parser('disable', help='disable rule')
        subcmd.add_argument('name', help='the name of the rule')
        addAuthenticatedCommand(subcmd, props)

        subcmd = parser.add_parser('status', help='get rule status')
        subcmd.add_argument('name', help='the name of the rule')
        addAuthenticatedCommand(subcmd, props)

        self.addDefaultCommands(parser, props, ['get', 'list'])

    def cmd(self, args, props):
        if args.subcmd == 'enable':
            return self.setState(args, props, True)
        elif args.subcmd == 'disable':
            return self.setState(args, props, False)
        elif args.subcmd == 'status':
            return self.getState(args, props)
        else:
            return super(Rule, self).cmd(args, props)

    def create(self, args, props, update):
        payload = { 'trigger': args.trigger, 'action': args.action }
        if args.shared:
            self.addPublish(payload, args)
        code = self.put(args, props, update, json.dumps(payload))
        if (code == 0 and 'enable' in args and args.enable):
            return self.setState(args, props, True)
        else:
            return code

    def preProcessDelete(self, args, props):
        if (args.disable):
            return self.setState(args, props, False)
        else:
            return 0

    def setState(self, args, props, enable):
        namespace, pname = parseQName(args.name, props)
        desc = 'active' if enable else 'inactive'
        status = json.dumps({ 'status': desc })
        url = '%(apibase)s/namespaces/%(namespace)s/rules/%(name)s' % {
            'apibase': apiBase(props),
            'namespace': urllib.quote(namespace),
            'name': self.getSafeName(pname)
        }
        headers = {
            'Content-Type': 'application/json'
        }

        res = request('POST', url, status, headers, auth=args.auth, verbose=args.verbose)
        if res.status == httplib.OK:
            print 'ok: rule %(name)s is %(desc)s' % {'desc': desc, 'name': args.name}
            return 0
        elif res.status == httplib.ACCEPTED:
            desc = 'activating' if enable else 'deactivating'
            print 'ok: rule %(name)s is %(desc)s' % {'desc': desc, 'name': args.name}
            return 0
        else:
            return responseError(res)

    def getState(self, args, props):
        namespace, pname = parseQName(args.name, props)
        url = '%(apibase)s/namespaces/%(namespace)s/rules/%(name)s' % {
            'apibase': apiBase(props),
            'namespace': urllib.quote(namespace),
            'name': self.getSafeName(pname)
        }

        res = request('GET', url, auth=args.auth, verbose=args.verbose)

        if res.status == httplib.OK:
            result = json.loads(res.read())
            print 'ok: rule %(name)s is %(status)s' % { 'name': args.name, 'status': result['status'] }
            return 0
        else:
            return responseError(res)

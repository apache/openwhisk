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
import sys
import urllib
from wskitem import Item
from wskutil import (addAuthenticatedCommand, request, getParams,
                     getAnnotations, responseError, parseQName, getQName,
                     hilite, apiBase)


#
# 'wsk packages' CLI
#
class Package(Item):

    def __init__(self):
        super(Package, self).__init__('package', 'packages')

    def getItemSpecificCommands(self, parser, props):
        subcmd = parser.add_parser('create', help='create a new package')
        subcmd.add_argument('name', help='the name of the package')
        addAuthenticatedCommand(subcmd, props)
        subcmd.add_argument('-a', '--annotation', help='annotations', nargs=2, action='append')
        subcmd.add_argument('-p', '--param', help='default parameters', nargs=2, action='append')
        subcmd.add_argument('--shared', nargs='?', const='yes', choices=['yes', 'no'], help='shared action (default: private)')

        subcmd = parser.add_parser('update', help='update an existing package')
        subcmd.add_argument('name', help='the name of the package')
        addAuthenticatedCommand(subcmd, props)
        subcmd.add_argument('-a', '--annotation', help='annotations', nargs=2, action='append')
        subcmd.add_argument('-p', '--param', help='default parameters', nargs=2, action='append')
        subcmd.add_argument('--shared', nargs='?', const='yes', choices=['yes', 'no'], help='shared action (default: private)')

        subcmd = parser.add_parser('bind', help='bind parameters to the package')
        subcmd.add_argument('package', help='the name of the package')
        subcmd.add_argument('name', help='the name of the bound package')
        addAuthenticatedCommand(subcmd, props)
        subcmd.add_argument('-a', '--annotation', help='annotations', nargs=2, action='append')
        subcmd.add_argument('-p', '--param', help='default parameters', nargs=2, action='append')

        subcmd = parser.add_parser('refresh', help='refresh package bindings')
        subcmd.add_argument('name', nargs='?', help='the namespace to refresh')
        addAuthenticatedCommand(subcmd, props)

        self.addDefaultCommands(parser, props)

    def cmd(self, args, props):
        if args.subcmd == 'refresh':
            return self.refresh(args, props)
        if args.subcmd == 'bind':
            return self.bind(args, props)
        else:
            return super(Package, self).cmd(args, props)

    def create(self, args, props, update):
        payload = {}
        if args.annotation:
            payload['annotations'] = getAnnotations(args)
        if args.param:
            payload['parameters'] = getParams(args)
        if args.shared:
            self.addPublish(payload, args)
        return self.put(args, props, update, json.dumps(payload))

    def bind(self, args, props):
        namespace, pname = parseQName(args.name, props)
        url = '%(apibase)s/namespaces/%(namespace)s/packages/%(name)s' % {
            'apibase': apiBase(props),
            'namespace': urllib.quote(namespace),
            'name': self.getSafeName(pname)
        }
        pkgNamespace, pkgName = parseQName(args.package, props)
        if not pkgName:
            print('package name malformed. name or /namespace/name allowed')
            sys.exit(1)
        binding = {'namespace': pkgNamespace, 'name': pkgName}
        payload = {
            'binding': binding,
            'annotations': getAnnotations(args),
            'parameters': getParams(args)
        }
        args.shared = False
        self.addPublish(payload, args)
        headers = {'Content-Type': 'application/json'}
        res = request('PUT', url, json.dumps(payload), headers, auth=args.auth,
                      verbose=args.verbose)
        if res.status == httplib.OK:
            print('ok: created binding %(name)s ' % {'name': args.name})
            return 0
        else:
            return responseError(res)

    def refresh(self, args, props):
        namespace, _ = parseQName(args.name, props)
        url = '%(apibase)s/namespaces/%(namespace)s/packages/refresh' % {
            'apibase': apiBase(props),
            'namespace': urllib.quote(namespace)
        }

        res = request('POST', url, auth=args.auth, verbose=args.verbose)
        if res.status == httplib.OK:
            result = json.loads(res.read())
            print('%(namespace)s refreshed successfully!' % {'namespace':
                                                             args.name})
            print(hilite('created bindings:', True))
            print('\n'.join(result['added']))
            print(hilite('updated bindings:', True))
            print('\n'.join(result['updated']))
            print(hilite('deleted bindings:', True))
            print('\n'.join(result['deleted']))
            return 0
        elif res.status == httplib.NOT_IMPLEMENTED:
            print('error: This feature is not implemented in the targeted '
                  'deployment')
            return responseError(res)
        else:
            result = json.loads(res.read())
            print('error: %(error)s' % {'error': result['error']})
            return responseError(res)

    def formatListEntity(self, e):
        ns = e['namespace']
        name = getQName(e['name'], ns)
        return '{:<65} {:<8}{:<7}'.format(
                name,
                'shared' if (e['publish'] or e['publish'] == 'true') else 'private',
                'binding' if e['binding'] else '')

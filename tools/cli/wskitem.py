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

from wskutil import apiBase, bold, request, responseError, parseQName, getQName, getPrettyJson, getParameterNamesFromAnnotations, getDescriptionFromAnnotations
import urllib
import abc
import json
import httplib
import sys

#
# Common superclass for action, trigger, and rule CLI commands.
# All of these share some common CRUD CLI commands, defined here.
#
class Item:
    __metaclass__ = abc.ABCMeta

    name = False
    collection = False
   
    # @param name: the singular form of the noun -- used on the command line
    # @parma collection:  the plural form of the noun -- used in REST APIs
    def __init__(self, name, collection):
        self.name = name
        self.collection = collection

    def getCommands(self, parser, props):
        commands = parser.add_parser(self.name, help='work with %s' % self.collection)
        subcmds  = commands.add_subparsers(title='available commands', dest='subcmd')
        self.getItemSpecificCommands(subcmds, props)

    # default commands are get, delete and list
    def addDefaultCommands(self, subcmds, props, which = ['get', 'delete', 'list']):
        if ('get' in which):
            subcmd = subcmds.add_parser('get', help='get %s' % self.name)
            subcmd.add_argument('name', help='the name of the %s' % self.name)
            subcmd.add_argument('project', nargs='?', help='project only this property')
            subcmd.add_argument('-s', '--summary', help='summarize entity details', action='store_true')
            subcmd.add_argument('-u', '--auth', help='authorization key', default=props.get('AUTH'))

        if ('delete' in which):
            subcmd = subcmds.add_parser('delete', help='delete %s' % self.name)
            subcmd.add_argument('name', help='the name of the %s' % self.name)
            subcmd.add_argument('-u', '--auth', help='authorization key', default=props.get('AUTH'))

        if ('list' in which):
            subcmd = subcmds.add_parser('list', help='list all %s' % self.collection)
            subcmd.add_argument('name', nargs='?', help='the namespace to list')
            subcmd.add_argument('-u', '--auth', help='authorization key', default=props.get('AUTH'))
            subcmd.add_argument('-s', '--skip', help='skip this many entities from the head of the collection', type=int, default=0)
            subcmd.add_argument('-l', '--limit', help='only return this many entities from the collection', type=int, default=30)

    def cmd(self, args, props):
        if args.subcmd == 'create':
            return self.create(args, props, False)
        elif args.subcmd == 'update':
            return self.create(args, props, True)
        elif args.subcmd == 'get':
            return self.get(args, props)
        elif args.subcmd == 'list':
            return self.list(args, props)
        elif args.subcmd == 'delete':
            return self.delete(args, props)
        else:
            print 'error: unexpected sub command'
            return 2

    @abc.abstractmethod
    def getItemSpecificCommands(self, parser, props):
        """add command parsers specific to item"""
        return parser

    @abc.abstractmethod
    def create(self, args, props, update):
        """creates item"""
        return 2

    # Return summary string of an entity.
    def getEntitySummary(self, entity, includeParams = True, kind = None, namespace = None):
        kind = self.name if kind is None else kind
        namespace = entity['namespace'] if 'namespace' in entity else namespace
        fullName = getQName(entity['name'], namespace)
        annotations = entity['annotations']
        description = getDescriptionFromAnnotations(annotations)
        summary = '%s %s' % (bold(kind), fullName)
        if description:
            summary += ': %s' % (description)
        if includeParams:
            parameterNames = getParameterNamesFromAnnotations(annotations)
            if parameterNames:
                summary += '\n   (%s: %s)' % (bold('params'), ' '.join(parameterNames))
        if 'actions' in entity:
            for a in entity['actions']:
                actionSummary = self.getEntitySummary(a, False, 'action', fullName)
                summary += '\n %s' % (actionSummary)
        if 'feeds' in entity:
            for a in entity['feeds']:
                actionSummary = self.getEntitySummary(a, False, 'feed  ', fullName)
                summary += '\n %s' % (actionSummary)
        return summary

    # allows "get" response to be post processed before rendering
    def postProcessGet(self, entity):
        return entity

    # allows "delete" pre-processing, override as needed
    def preProcessDelete(self, args, props):
        return 0

    def put(self, args, props, update, payload):
        res = self.httpPut(args, props, update, payload)
        return self.putResponse(res, update)
            
    def get(self, args, props):
        res = self.httpGet(args, props)
        if res.status == httplib.OK:
            result = self.postProcessGet(json.loads(res.read()))
            if args.summary:
                summary = self.getEntitySummary(result)
                print summary
            elif args.project:
                if args.project in result:
                    print 'ok: got %(item)s %(name)s, projecting %(p)s' % {'item': self.name, 'name': args.name, 'p': args.project }
                    print getPrettyJson(result[args.project])
                    return 0
                else:
                    print 'ok: got %(item)s %(name)s, but it does not contain property %(p)s' % {'item': self.name, 'name': args.name, 'p': args.project }
                    return 148
            else:
                print 'ok: got %(item)s %(name)s' % {'item': self.name, 'name': args.name }
                print getPrettyJson(result)
                return 0
        else:
            return responseError(res)

    def delete(self, args, props):
        res = self.httpDelete(args, props)
        return self.deleteResponse(args, res)

    def list(self, args, props):
        namespace, pname = parseQName(args.name, props)
        if pname:
            pname = ('/%s' % pname) if pname.endswith('/') else '/%s/' % pname
        url = 'https://%(apibase)s/namespaces/%(namespace)s/%(collection)s%(package)s?skip=%(skip)s&limit=%(limit)s%(public)s' % {
            'apibase': apiBase(props),
            'namespace': urllib.quote(namespace),
            'collection': self.collection,
            'package': pname if pname else '',
            'skip': args.skip,
            'limit': args.limit,
            'public': '&public=true' if 'shared' in args and args.shared else ''
        }

        res = request('GET', url, auth=args.auth, verbose=args.verbose)

        if res.status == httplib.OK:
            result = json.loads(res.read())
            print bold(self.collection)
            for e in result:
                print self.formatListEntity(e)
            return 0
        else:
            return responseError(res)

    # returns the HTTP response for saving an item.
    def httpPut(self, args, props, update, payload):
        namespace, pname = parseQName(args.name, props)
        url = 'https://%(apibase)s/namespaces/%(namespace)s/%(collection)s/%(name)s%(update)s' % {
            'apibase': apiBase(props),
            'namespace': urllib.quote(namespace),
            'collection': self.collection,
            'name': self.getSafeName(pname),
            'update': '?overwrite=true' if update else ''
        }
        
        headers= {
            'Content-Type': 'application/json'
        }

        res = request('PUT', url, payload, headers, auth=args.auth, verbose=args.verbose)
        return res

    # returns the HTTP response of getting an item.
    def httpGet(self, args, props, name = None):
        if name is None:
            name = args.name
        namespace, pname = parseQName(name, props)

        if pname is None or pname.strip() == '':
            print 'error: entity name missing, did you mean to list collection'
            sys.exit(2)
        url = 'https://%(apibase)s/namespaces/%(namespace)s/%(collection)s/%(name)s' % {
            'apibase': apiBase(props),
            'namespace': urllib.quote(namespace),
            'collection': self.collection,
            'name': self.getSafeName(pname)
        }
        
        return request('GET', url, auth=args.auth, verbose=args.verbose)

    # returns the HTTP response for deleting an item.
    def httpDelete(self, args, props):
        code = self.preProcessDelete(args, props)
        if (code != 0):
            return code

        namespace, pname = parseQName(args.name, props)
        url = 'https://%(apibase)s/namespaces/%(namespace)s/%(collection)s/%(name)s' % {
            'apibase': apiBase(props),
            'namespace': urllib.quote(namespace),
            'collection': self.collection,
            'name': self.getSafeName(pname)
        }

        res = request('DELETE', url, auth=args.auth, verbose=args.verbose)
        return res

    # processes delete response and emit console message
    def deleteResponse(self, args, res):
        if res.status == httplib.OK:
            print 'ok: deleted %(name)s' % {'name': args.name }
            return 0
        else:
            return responseError(res)

    # process put response and emit console message
    def putResponse(self, res, update):
        if res.status == httplib.OK:
            result = json.loads(res.read())
            print 'ok: %(mode)s %(item)s %(name)s' % {
                  'mode': 'updated' if update else 'created',
                  'item': self.name,
                  'name': result['name']
            }
            return 0
        else:
            return responseError(res)

    # returns a name escaped so it can be used in a url.
    def getSafeName(self, name):
        safeChars = '@:./'
        return urllib.quote(name, safeChars)

    # adds publish parameter to payloads
    def addPublish(self, payload, args):
        if args.shared != None and not ('update' in args and args.update):
            payload['publish'] = True if args.shared == 'yes' else False

    # formats an entity for printing in a list
    def formatListEntity(self, e):
        ns = e['namespace']
        name = getQName(e['name'], ns)
        return '{:<65} {:<8}'.format(name, 'shared' if (e['publish'] or e['publish'] == 'true') else 'private')

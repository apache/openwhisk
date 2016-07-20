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
from wskaction import Action
from wskutil import addAuthenticatedCommand, apiBase, dict2obj, getParam, getParams, getActivationArgument, getAnnotations, parseQName, responseError, request, getQName
import urllib

class Trigger(Item):

    def __init__(self):
        super(Trigger, self).__init__('trigger', 'triggers')

    def getItemSpecificCommands(self, parser, props):
        subcmd = parser.add_parser('create', help='create new trigger')
        subcmd.add_argument('name', help='the name of the trigger')
        addAuthenticatedCommand(subcmd, props)
        subcmd.add_argument('--shared', nargs='?', const='yes', choices=['yes', 'no'], help='shared action (default: private)')
        subcmd.add_argument('-a', '--annotation', help='annotations', nargs=2, action='append')
        subcmd.add_argument('-p', '--param', help='default parameters', nargs=2, action='append')
        subcmd.add_argument('-f', '--feed', help='trigger feed')

        subcmd = parser.add_parser('update', help='update an existing trigger')
        subcmd.add_argument('name', help='the name of the trigger')
        addAuthenticatedCommand(subcmd, props)
        subcmd.add_argument('--shared', nargs='?', const='yes', choices=['yes', 'no'], help='shared action (default: private)')
        subcmd.add_argument('-a', '--annotation', help='annotations', nargs=2, action='append')
        subcmd.add_argument('-p', '--param', help='default parameters', nargs=2, action='append')

        subcmd = parser.add_parser('fire', help='fire trigger event')
        subcmd.add_argument('name', help='the name of the trigger')
        subcmd.add_argument('payload', help='the payload to attach to the trigger', nargs ='?')
        addAuthenticatedCommand(subcmd, props)
        subcmd.add_argument('-p', '--param', help='parameters', nargs=2, action='append')

        self.addDefaultCommands(parser, props)

    def cmd(self, args, props):
        if args.subcmd == 'fire':
            return self.fire(args, props)
        else:
            return super(Trigger, self).cmd(args, props)

    def create(self, args, props, update):
        annotations = getAnnotations(args)
        parameters  = getParams(args)
        createFeed  = 'feed' in args and args.feed

        if createFeed:
            annotations.append(getParam('feed', args.feed))
            # if creating a trigger feed, parameters are passed to
            # the feed action, not the trigger
            parameters = []

        payload = {}
        if annotations:
            payload['annotations'] = annotations
        if parameters:
            payload['parameters'] = parameters
        if args.shared:
            self.addPublish(payload, args)
        putResponse = self.httpPut(args, props, update, json.dumps(payload))
        if putResponse.status == httplib.OK and createFeed:
            return self.createFeed(args, props, putResponse)
        else:
            return self.putResponse(putResponse, update)

    def fire(self, args, props):
        namespace, pname = parseQName(args.name, props)
        url = '%(apibase)s/namespaces/%(namespace)s/triggers/%(name)s' % {
            'apibase': apiBase(props),
            'namespace': urllib.quote(namespace),
            'name': self.getSafeName(pname)
        }
        payload = json.dumps(getActivationArgument(args))
        headers= {
            'Content-Type': 'application/json'
        }
        res = request('POST', url, payload, headers, auth=args.auth, verbose=args.verbose)

        if res.status == httplib.OK:
            result = json.loads(res.read())
            print 'ok: triggered %(name)s with id %(id)s' % {'name': args.name, 'id': result['activationId'] }
            return 0
        else:
            return responseError(res)

    def delete(self, args, props):
        res = self.httpDelete(args, props)
        if res.status == httplib.OK:
            return self.deleteFeed(args, props, res)
        else:
            return responseError(res)

    # invokes feed action to establish feed
    def createFeed(self, args, props, putResponse):
        ns = props['namespace']
        triggerName = getQName(args.name, ns)

        parameters = args.param
        if parameters is None:
            parameters = []
        parameters.append([ 'lifecycleEvent', 'CREATE' ])
        parameters.append([ 'triggerName', triggerName ])
        parameters.append([ 'authKey', args.auth ])

        feedArgs = {
            'verbose': args.verbose,
            'name' : args.feed,
            'param': parameters,
            'blocking': True,
            'auth': args.auth
        }

        try:
            feedResponse = Action().doInvoke(dict2obj(feedArgs), props)
        except Exception as e:
            print 'exception: %s' % e
            feedResponse = None

        if feedResponse and feedResponse.status == httplib.OK:
            print 'ok: created %s feed %s' % (self.name, args.name)
        else:
            print 'error: failed to create %s feed %s' % (self.name, args.name)
            # clean up by deleting trigger
            self.httpDelete(args, props)
            return responseError(feedResponse, None) if feedResponse else 1

    def deleteFeed(self, args, props, res):
        trigger = json.loads(res.read())
        hasFeed = [a['value'] for a in  trigger['annotations'] if a['key'] == 'feed']
        feedName = hasFeed[0] if hasFeed else None

        if feedName:
            ns = props['namespace']
            triggerName = getQName(args.name, ns)

            parameters = []
            parameters.append([ 'lifecycleEvent', 'DELETE' ])
            parameters.append([ 'triggerName', triggerName ])
            parameters.append([ 'authKey', args.auth ])

            feedArgs = {
                'verbose': args.verbose,
                'name' : feedName,
                'param': parameters,
                'blocking': True,
                'auth': args.auth
            }

            try:
                feedResponse = Action().doInvoke(dict2obj(feedArgs), props)
            except Exception as e:
                print 'exception: %s' % e
                feedResponse = None

            if feedResponse and feedResponse.status == httplib.OK:
                return self.deleteResponse(args, res)
            else:
                print 'error: failed to delete %s feed %s but did delete the trigger' % (self.name, args.name)
                return responseError(feedResponse, None) if feedResponse else 1

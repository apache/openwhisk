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
import urllib
import time
from datetime import datetime, timedelta
import copy
from wskitem import Item
from wskutil import addAuthenticatedCommand, apiBase, bold, parseQName, getQName, request, responseError, getPrettyJson

# how many seconds to sleep between polls
SLEEP_SECONDS = 2

# a parameter -- how many seconds might it take for an activation
# completion to propagate into cloudant
SLACK_SECONDS = 5

# the timestamp of the latest activation we have already reported
lastTime = 0

#
# 'wsk activations' CLI
#
class Activation(Item):

    def __init__(self):
        super(Activation, self).__init__('activation', 'activations')

    def getItemSpecificCommands(self, parser, props):
        subcmd = parser.add_parser('list', help='retrieve activations')
        subcmd.add_argument('name', nargs='?', help='the namespace or action to list')
        addAuthenticatedCommand(subcmd, props)
        subcmd.add_argument('-s', '--skip', help='skip this many entities from the head of the collection', type=int, default=0)
        subcmd.add_argument('-l', '--limit', help='only return this many entities from the collection', type=int, default=30)
        subcmd.add_argument('-f', '--full', help='return full documents for each activation', action='store_true')
        subcmd.add_argument('--upto', help='return activations with timestamps earlier than UPTO; measured in milliseconds since Thu, 01 Jan 1970 00:00:00',  type=long, default=0)
        subcmd.add_argument('--since', help='return activations with timestamps later than SINCE; measured in milliseconds since Thu, 01 Jan 1970 00:00:00', type=long, default=0)

        subcmd = parser.add_parser('get', help='get %s' % self.name)
        subcmd.add_argument('name', help='the name of the %s' % self.name)
        subcmd.add_argument('project', nargs='?', help='project only this property')
        addAuthenticatedCommand(subcmd, props)
        subcmd.add_argument('-s', '--summary', help='summarize entity details', action='store_true')

        subcmd = parser.add_parser('logs', help='get the logs of an activation')
        subcmd.add_argument('id', help='the activation id')
        addAuthenticatedCommand(subcmd, props)
        subcmd.add_argument('-s', '--strip', help='strip timestamp and stream information', action='store_true')

        subcmd= parser.add_parser('result', help='get the result of an activation')
        subcmd.add_argument('id', help='the invocation id')
        addAuthenticatedCommand(subcmd, props)

        # poll
        subcmd= parser.add_parser('poll', help='poll continuously for log messages from currently running actions')
        subcmd.add_argument('name', nargs='?', help='the namespace to poll')
        addAuthenticatedCommand(subcmd, props)
        subcmd.add_argument('-e', '--exit', help='exit after this many seconds', type=int, default=-1)
        subcmd.add_argument('-ss', '--since-seconds', help='start polling for activations this many seconds ago', type=long, default=0, dest='since_secs')
        subcmd.add_argument('-sm', '--since-minutes', help='start polling for activations this many minutes ago', type=long, default=0, dest='since_mins')
        subcmd.add_argument('-sh' ,'--since-hours', help='start polling for activations this many hours ago', type=long, default=0, dest='since_hrs')
        subcmd.add_argument('-sd' ,'--since-days', help='start polling for activations this many days ago', type=long, default=0, dest='since_days')

    def cmd(self, args, props):
        if args.subcmd == 'list':
            return self.list(args, props)
        elif args.subcmd == 'get':
            return self.get(args, props)
        elif args.subcmd == 'logs':
            return self.logs(args, props)
        elif args.subcmd == 'result':
            return self.result(args, props)
        elif args.subcmd == 'poll':
            return self.poll(args, props)
        else:
            print 'error: unexpected sub command'
            return 2

    def create(self, args, props, update):
        """not allowed"""
        return 2

    def postProcess(self, entity):
        #entity['logs'] = json.loads(entity['logs'])
        return entity

    def get(self, args, props):
        args.name = getQName(args.name, '_') # kludge: use default namespace unless explicitly specified
        return Item.get(self, args, props)

    def list(self, args, props):
        name = args.name if args.name else '/_'
        args.name = getQName(name, '_') # kludge: use default namespace unless explicitly specified
        res = self.listCmd(args, props)
        if res.status == httplib.OK:
            result = json.loads(res.read())
            print bold('activations')
            for a in result:
                if args.full:
                    print getPrettyJson(a)
                else:
                    print '{:<45}{:<40}'.format(a['activationId'], a['name'])
            return 0
        else:
            return responseError(res)

    def getEntitySummary(self, entity):
        kind = self.name
        fullName = getQName(entity['name'], entity['namespace'])
        end = datetime.fromtimestamp(entity['end'] / 1000)
        status = entity['response']['status']
        result = getPrettyJson(entity['response']['result'])
        summary = '%s result for %s (%s at %s):\n%s' % (kind, fullName, status, end, result)
        return summary

    def result(self, args, props):
        fqid = getQName(args.id, '_') # kludge: use default namespace unless explicitly specified
        namespace, aid = parseQName(fqid, props)
        url = '%(apibase)s/namespaces/%(namespace)s/activations/%(id)s/result' % {
           'apibase': apiBase(props),
           'namespace': urllib.quote(namespace),
           'id': aid
        }

        res = request('GET', url, auth=args.auth, verbose=args.verbose)

        if res.status == httplib.OK:
            response = json.loads(res.read())
            if 'result' in response:
                result = response['result']
                print getPrettyJson(result)
            return 0
        else:
            return responseError(res)

    def logs(self, args, props):
        fqid = getQName(args.id, '_') # kludge: use default namespace unless explicitly specified
        namespace, aid = parseQName(fqid, props)
        url = '%(apibase)s/namespaces/%(namespace)s/activations/%(id)s/logs' % {
           'apibase': apiBase(props),
           'namespace': urllib.quote(namespace),
           'id': aid
        }

        res = request('GET', url, auth=args.auth, verbose=args.verbose)

        if res.status == httplib.OK:
            result = json.loads(res.read())
            logs = result['logs']
            if args.strip:
                logs = map(stripTimeStampAndString, logs)
            print '\n'.join(logs)
            return 0
        else:
            return responseError(res)

    # implementation of wsk activations poll
    def poll(self, args, props):
        name = args.name if args.name else '/_'
        args.name = getQName(name, '_') # kludge: use default namespace unless explicitly specified
        print 'Hit Ctrl-C to exit.'
        try:
            self.console(args, props)
        except KeyboardInterrupt:
            print ''

    # return the result of a 'wsk activation list' call, an HTTPResponse
    def listCmd(self, args, props):
        namespace, pname = parseQName(args.name, props)
        url = '%(apibase)s/namespaces/%(namespace)s/activations?docs=%(full)s&skip=%(skip)s&limit=%(limit)s&%(filter)s' % {
            'apibase': apiBase(props),
            'namespace': urllib.quote(namespace),
            'collection': self.collection,
            'full': 'true' if args.full else 'false',
            'skip': args.skip,
            'limit': args.limit,
            'filter': 'name=%s' % urllib.quote(pname) if pname and len(pname) > 0 else ''
        }
        if args.upto > 0:
            url = url + '&upto=' + str(args.upto)
        if args.since > 0:
            url = url + '&since=' + str(args.since)

        res = request('GET', url, auth=args.auth, verbose=args.verbose)
        return res

    #
    # main routine for polling loop
    #
    def console(self, args, props):
        global lastTime

        if args.since_secs == args.since_mins == args.since_hrs == args.since_days == 0:
            # initialize lastTime to just past the most recent activation
            lastActivation = self.fetchMostRecent(args, props)
            if lastActivation != None:
                lastTime = extractTimestamp(lastActivation) + 1 + SLACK_SECONDS * 1000
        else:
            # initialize lastTime to utcnow - args.since_[secs,mins,hrs,days]
            n = datetime.utcnow()
            d = timedelta(seconds=args.since_secs, minutes=args.since_mins, hours=args.since_hrs, days=args.since_days)
            e = datetime(1970, 1, 1)
            lastTime = int((n-d-e).total_seconds()*1000)

        reported = set()
        localStartTime = int(time.time())
        print 'Polling for logs'
        while True:
            if (args.exit > 0):
                localDuration = int(time.time()) - localStartTime
                if (localDuration > args.exit):
                    return
            L = self.fetchActivations(lastTime, args, props)
            if L:
                printLogs(reversed(L), reported)
                time.sleep(SLEEP_SECONDS)

    #
    # Fetch the most recent activation
    #
    # Return the activation record, or None
    #
    def fetchMostRecent(self, args, props):
        a = copy.deepcopy(args)
        a.action = None
        a.name = args.name
        a.full = True
        a.skip = 0
        a.limit = 1
        a.upto = 0
        a.since = 0
        pr = copy.deepcopy(props)
        res = self.listCmd(a, pr)
        if res.status == httplib.OK:
            result = json.loads(res.read())
            return None if len(result) == 0 else result[0]
        else:
            responseError(res)
            return None

    #
    # Fetch all activation reconds since a timestamp
    #
    def fetchActivations(self, beginMillis, args, props):
        # fetch all activations starting from SLACK_SECONDS seconds in the past
        if beginMillis > SLACK_SECONDS * 1000:
            beginMillis = beginMillis - (SLACK_SECONDS * 1000);

        a = copy.deepcopy(args)
        a.name = args.name
        a.full = True
        a.skip = 0
        a.limit = 0
        a.upto = 0
        a.since = beginMillis
        pr = copy.deepcopy(props)
        res = self.listCmd(a, pr)
        if res.status == httplib.OK:
            result = json.loads(res.read())
            return result
        else:
            responseError(res)
            return None

#
# Extract the timestamp from an activation, or return 0
#
def extractTimestamp(activation):
    return activation['start']

#
# Print all the logs for an activation record
#
# auth: whisk auth key
#
def printLogsForActivation(activation):
    global lastTime
    print 'Activation: %s (%s)' % (activation['name'], activation['activationId'])

    if 'logs' in activation:
        for element in activation['logs']:
            print element
        # update the lastTime global timestamp if necessary to record the latest
        # start time yet fetched
        if activation['start'] > lastTime:
            lastTime = activation['start']
#
# Print all the logs for a list of activation records
#
# L: set of activation records
# reported: set of ids already reported to the user
#
def printLogs(L, reported):
    for a in L:
        if not a['activationId'] in reported:
            reported.add(a['activationId'])
            printLogsForActivation(a)

#
# Strips timestamp and stream information from log line
#
def stripTimeStampAndString(line):
    # log line should be formatted to fixed width and stripping
    # first 38 charecters should do
    return line[39:]

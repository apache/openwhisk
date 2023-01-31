#!/usr/bin/env python
"""Executable Python script for testing the action proxy.
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

  This script is useful for testing the action proxy (or its derivatives)
  by simulating invoker interactions. Use it in combination with
  docker run <image> which starts up the action proxy.
  Example:
     docker run -i -t -p 8080:8080 dockerskeleton # locally built images may be referenced without a tag
     ./invoke.py init <action source file>
     ./invoke.py run '{"some":"json object as a string"}'

  For additional help, try ./invoke.py -h
"""

import os
import re
import sys
import json
import base64
import requests
import codecs
import argparse
try:
    import argcomplete
except ImportError:
    argcomplete = False

def main():
    try:
        args = parseArgs()
        exitCode = {
            'init' : init,
            'run'   : run
        }[args.cmd](args)
    except Exception as e:
        print(e)
        exitCode = 1
    sys.exit(exitCode)

def dockerHost():
    dockerHost = 'localhost'
    if 'DOCKER_HOST' in os.environ:
        try:
            dockerHost = re.compile('tcp://(.*):[\d]+').findall(os.environ['DOCKER_HOST'])[0]
        except Exception:
            print('cannot determine docker host from %s' % os.environ['DOCKER_HOST'])
            sys.exit(-1)
    return dockerHost

def containerRoute(args, path):
    return 'http://%s:%s/%s' % (args.host, args.port, path)

class objectify(object):
    def __init__(self, d):
        self.__dict__ = d

def parseArgs():
    parser = argparse.ArgumentParser(description='initialize and run an OpenWhisk action container')
    parser.add_argument('-v', '--verbose', help='verbose output', action='store_true')
    parser.add_argument('--host', help='action container host', default=dockerHost())
    parser.add_argument('-p', '--port', help='action container port number', default=8080, type=int)

    subparsers = parser.add_subparsers(title='available commands', dest='cmd')

    initmenu = subparsers.add_parser('init', help='initialize container with src or zip/tgz file')
    initmenu.add_argument('-b', '--binary', help='treat artifact as binary', action='store_true')
    initmenu.add_argument('-r', '--run', nargs='?', default=None, help='run after init')
    initmenu.add_argument('main', nargs='?', default='main', help='name of the "main" entry method for the action')
    initmenu.add_argument('artifact', help='a source file or zip/tgz archive')
    initmenu.add_argument('env', nargs='?', help='the environment variables to export to the action, either a reference to a file or an inline JSON object', default=None)

    runmenu = subparsers.add_parser('run', help='send arguments to container to run action')
    runmenu.add_argument('payload', nargs='?', help='the arguments to send to the action, either a reference to a file or an inline JSON object', default=None)

    if argcomplete:
        argcomplete.autocomplete(parser)
    return parser.parse_args()

def init(args):
    main = args.main
    artifact = args.artifact

    if artifact and (args.binary or artifact.endswith('.zip') or artifact.endswith('tgz') or artifact.endswith('jar')):
        with open(artifact, 'rb') as fp:
            contents = fp.read()
        contents = str(base64.b64encode(contents), 'utf-8')
        binary = True
    elif artifact != '':
        with(codecs.open(artifact, 'r', 'utf-8')) as fp:
            contents = fp.read()
        binary = False
    else:
        contents = None
        binary = False

    r = requests.post(
        containerRoute(args, 'init'),
        json = {
            "value": {
                "code": contents,
                "binary": binary,
                "main": main,
                "env": processPayload(args.env)
            }
        })

    print(r.text)

    if r.status_code == 200 and args.run != None:
        runArgs = objectify({})
        runArgs.__dict__ = args.__dict__.copy()
        runArgs.payload = args.run
        run(runArgs)

def run(args):
    value = processPayload(args.payload)
    if args.verbose:
        print('Sending value: %s...' % json.dumps(value)[0:40])
    r = requests.post(containerRoute(args, 'run'), json = {"value": value})
    print(str(r.content, 'utf-8'))

def processPayload(payload):
    if payload and os.path.exists(payload):
        with open(payload) as fp:
            return json.load(fp)
    try:
        d = json.loads(payload if payload else '{}')
        if isinstance(d, dict):
            return d
        else:
            raise
    except:
        print('payload must be a JSON object.')
        sys.exit(-1)

if __name__ == '__main__':
    main()

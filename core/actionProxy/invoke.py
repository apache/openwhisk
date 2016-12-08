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

# This script is useful for testing the action proxy (or its derivatives)
# by simulating invoker interactions. Use it in combination with
# delete-build-run.sh which builds and starts up the action proxy.
# Examples:
#    ./delete-build-run.sh &
#    ./invoke.py init <action source file> # should return OK
#    ./invoke.py run '{"some":"json object as a string"}'

import os
import re
import sys
import json
import requests
import codecs

DOCKER_HOST = "localhost"
if "DOCKER_HOST" in os.environ:
    try:
        DOCKER_HOST = re.compile("tcp://(.*):[\d]+").findall(os.environ["DOCKER_HOST"])[0]
    except Exception:
        print("cannot determine docker host from %s" % os.environ["DOCKER_HOST"])
        sys.exit(-1)
DEST="http://%s:8080" % DOCKER_HOST

def content_from_args(args):
    if len(args) == 0:
        return None

    if len(args) == 1 and os.path.exists(args[0]):
        with open(args[0]) as fp:
            return json.load(fp)

    # else...
    in_str = " ".join(args)
    try:
        d = json.loads(in_str)
        if isinstance(d, dict):
            return d
        else:
            raise "Not a dict."
    except:
        return in_str

def init(args):
    kind = "code"
    if args.endswith(".zip"):
        with open(args, "rb") as fp:
            contents = fp.read().encode("base64")
        binary = True
    else:
        with(codecs.open(args, "r", "utf-8")) as fp:
            contents = fp.read()
        binary = False
    r = requests.post("%s/init" % DEST, json.dumps({ "value" : { kind: contents, binary: binary } }))
    print r.text

def run(args):
    value = content_from_args(args)
    print "Sending value: %s..." % json.dumps(value)[0:40]
    r = requests.post("%s/run" % DEST, json.dumps({ "value" : value }))
    print r.text

if sys.argv[1] == "init":
    init(sys.argv[2])
elif sys.argv[1] == "run":
    run(sys.argv[2:])
else:
    print("usage: 'init <filename>' or 'run JSON-as-string'")

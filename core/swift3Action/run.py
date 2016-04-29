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

import os
import sys
import json
import requests
import codecs

DEST="http://localhost:8080/run"

def content_from_args(args):
    if len(args) == 0:
        return {}

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
        return { "payload" : " ".join(sys.argv[1:]) }


value = content_from_args(sys.argv[1:])

print "Sending value: %s..." % json.dumps(value)[0:40]

r = requests.post(DEST, json.dumps({ "value" : value }))

print r.text

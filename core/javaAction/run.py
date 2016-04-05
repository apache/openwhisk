import os
import sys
import json
import requests
import codecs

HOST=os.environ["CONTAINER"]
if HOST == "":
    HOST = "localhost"

DEST="http://%s:8080/run" % HOST

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

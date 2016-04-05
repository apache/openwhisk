import os
import sys
import json
import requests
import codecs
import base64

HOST=os.environ["CONTAINER"]
if HOST == "":
    HOST = "localhost"

DEST="http://%s:8080/init" % HOST

if os.path.isfile(sys.argv[1]):
    with open(sys.argv[1], "rb") as fp:
        encoded = base64.b64encode(fp.read())
else:
    encoded = sys.argv[1]

payload = {
    "value" : {
        "main" : sys.argv[2],
        "jar"  : encoded
    }
}

r = requests.post(DEST, json.dumps(payload, indent=2))

print r.text

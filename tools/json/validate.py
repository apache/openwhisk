#!/usr/bin/python
#
# Validate that a string conforms to a json schema
#
# usage: validate.py obj schema
# where obj and schema are both strings.
#
# prints 'true' if validate succeeds, 'false' otherwise

import sys
import json
from jsonschema import validate

#print len(sys.argv)
#print sys.argv

if len(sys.argv) != 3:
    print 'usage: validate.py obj schema'
    sys.exit(-1)

a1=sys.argv[1].replace('\n','');
a2=sys.argv[2].replace('\n','');
obj=json.loads(a1)
schema=json.loads(a2)

try:
    validate(obj,schema)
    print 'true'
except:
    print 'false'

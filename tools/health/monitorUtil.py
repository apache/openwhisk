# Utility for health/RAS

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
import subprocess
import argparse
import datetime
import time

# Repeatedly run the check for delay seconds until it responds with a 0.
# If it never does, exit with the last failing code rather than 0.
def checkLoop(name, checker, delay) :
    now = datetime.datetime.now()
    end = now + datetime.timedelta(0,delay)
    while (now <= end):
        now = datetime.datetime.now()
        alive = checker()
        if (alive == 0):
            break;
        if (delay != 0):
            time.sleep(3)
    print "%s %s" % (name, "is alive" if alive == 0 else "is not responding")
    sys.exit(alive)


# Runs the given command with optional input.
# The command is run in a blocking fashion and the return code and output (both stdout/stderr combined) returned as a pair.
def run(cmd, inData="") :
    try:
        p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stdin=subprocess.PIPE, stderr=subprocess.STDOUT)
        if (inData != ""):
            p.stdin.write(inData)
        p.wait()
        output = p.stdout.read()
        rc = p.returncode
    except Exception as e: # catch *all* exceptions
        print "exec: died with exception ",
        print e
        print "    : ", cmd
        return (-1, str(e))
    return (rc, output)


# Run a given command and check its output - return 0 if it matches.
def outputChecker(cmd,expected,substring=0) :
    (rc,output) = run(cmd);
    if (rc != 0):
        return rc
    match = (output == expected) if (substring == 0) else (output.find(expected) != -1)
    if (match):
        return rc
    else:
        return -1

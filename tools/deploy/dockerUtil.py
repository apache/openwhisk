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

# Determine if a docker endpoint is alive.

import os
import sys
import subprocess
import argparse
import time

import monitorUtil

#
# Is the given docker endpoint (XX.XX.XX.XX:PP) alive?
#
def isDockerAlive(endpoint) :
    def inner() :
        print "docker endpoint is ", endpoint
        cmd = ['docker', '--host', endpoint, 'ps']
        okString = "CONTAINER ID"
        return monitorUtil.outputChecker(cmd,okString,1)
    return inner

#
# Run a docker command with the given endpoint and tls command (could be empty string).
#
def runDocker(dockerEndpoint, dockerTlsCmd, cmdArray):
  start = time.time()
  tlsArray = [dockerTlsCmd] if (dockerTlsCmd != "") else []
  dockerCmd = ['docker', '--host', 'tcp://'+dockerEndpoint] + tlsArray
  cmd = dockerCmd + cmdArray
  #print 'Running', cmd
  (rc, output) = monitorUtil.run(cmd)
  elapsed = round((time.time() - start) * 10) / 10
  #print 'code = ', rc
  #print output
  return (elapsed, rc, output)

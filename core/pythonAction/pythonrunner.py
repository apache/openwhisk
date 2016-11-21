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
import codecs
sys.path.append('../actionProxy')
from actionproxy import ActionRunner, main, setRunner
import json
import traceback

class PythonRunner(ActionRunner):

    def __init__(self):
        ActionRunner.__init__(self)
        self.fn = None
        self.mainFn = "main"

    def init(self, message):
        if 'code' in message:
            if 'main' in message:
                self.mainFn = message["main"]

            try:
                self.fn = compile(message["code"], filename = 'action', mode = 'exec')
            except Exception:
                traceback.print_exc(file = sys.stderr, limit = 0)
        return self.verify()

    def verify(self):
        return self.fn is not None

    def run(self, args, env):
        # initialize the namespace for the execution
        namespace = {}
        result = None
        try:
            os.environ = env
            namespace['param'] = args
            exec(self.fn, namespace)
            exec("fun = %s(param)" % self.mainFn, namespace)
            result = namespace['fun']
        except Exception:
            traceback.print_exc(file = sys.stderr)

        if result and isinstance(result, dict):
            return (200, result)
        else:
            return (502, { 'error': 'The action did not return a dictionary.'})

if __name__ == "__main__":
    setRunner(PythonRunner())
    main()

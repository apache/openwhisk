"""Executable Python script for running Python actions.

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
"""

import os
import sys
import codecs
import traceback
sys.path.append('../actionProxy')
from actionproxy import ActionRunner, main, setRunner


class PythonRunner(ActionRunner):

    def __init__(self):
        ActionRunner.__init__(self, '/action/__main__.py')
        self.fn = None
        self.mainFn = 'main'
        self.global_context = {}

    def initCodeFromString(self, message):
        # do nothing, defer to build step
        return True

    def build(self, message):
        binary = message['binary'] if 'binary' in message else False
        if not binary:
            code = message['code']
            filename = 'action'
        elif os.path.isfile(self.source):
            with codecs.open(self.source, 'r', 'utf-8') as m:
                code = m.read()
            workdir = os.path.dirname(self.source)
            sys.path.insert(0, workdir)
            os.chdir(workdir)
        else:
            sys.stderr.write('Zip file does not include ' + os.path.basename(self.source) + '\n')
            return False

        try:
            filename = os.path.basename(self.source)
            self.fn = compile(code, filename=filename, mode='exec')
            if 'main' in message:
                self.mainFn = message['main']

            # if the directory 'virtualenv' is extracted out of a zip file
            path_to_virtualenv = os.path.dirname(self.source) + '/virtualenv'
            if os.path.isdir(path_to_virtualenv):
                # activate the virtualenv using activate_this.py contained in the virtualenv
                activate_this_file = path_to_virtualenv + '/bin/activate_this.py'
                if os.path.exists(activate_this_file):
                    with open(activate_this_file) as f:
                        code = compile(f.read(), activate_this_file, 'exec')
                        exec(code, dict(__file__=activate_this_file))
                else:
                    sys.stderr.write('Invalid virtualenv. Zip file does not include /virtualenv/bin/' + os.path.basename(activate_this_file) + '\n')
                    return False
            return True
        except Exception:
            traceback.print_exc(file=sys.stderr, limit=0)
            return False

    def verify(self):
        return self.fn is not None

    def run(self, args, env):
        result = None
        try:
            os.environ = env
            self.global_context['param'] = args
            exec(self.fn, self.global_context)
            exec('fun = %s(param)' % self.mainFn, self.global_context)
            result = self.global_context['fun']
        except Exception:
            traceback.print_exc(file=sys.stderr)

        if result and isinstance(result, dict):
            return (200, result)
        else:
            return (502, {'error': 'The action did not return a dictionary.'})

if __name__ == '__main__':
    setRunner(PythonRunner())
    main()

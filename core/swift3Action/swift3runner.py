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
import json
sys.path.append('../actionProxy')
from actionproxy import ActionRunner, main, setRunner

SRC_EPILOGUE_FILE = './epilogue.swift'
DEST_SCRIPT_FILE = '/swift3Action/spm-build/main.swift'
DEST_SCRIPT_DIR = '/swift3Action/spm-build'
DEST_BIN_FILE = '/swift3Action/spm-build/.build/release/Action'

BUILD_PROCESS = [ './swiftbuildandlink.sh' ]

class Swift3Runner(ActionRunner):

    def __init__(self):
        ActionRunner.__init__(self, DEST_SCRIPT_FILE, DEST_BIN_FILE)

    def epilogue(self, init_message):
        # skip if executable already exists (was unzipped)
        if os.path.isfile(self.binary):
            return

        if 'main' in init_message:
            main_function = init_message['main']
        else:
            main_function = 'main'

        with codecs.open(DEST_SCRIPT_FILE, 'a', 'utf-8') as fp:
            with codecs.open(SRC_EPILOGUE_FILE, 'r', 'utf-8') as ep:
                fp.write(ep.read())
            fp.write('_run_main(mainFunction: %s)\n' % main_function)

    def build(self, init_message):
        # short circuit the build, if there already exists a binary
        # from the zip file
        if os.path.isfile(self.binary):
            # file may not have executable permission, set it
            os.chmod(self.binary, 0555)
            return

        p = subprocess.Popen(BUILD_PROCESS, cwd=DEST_SCRIPT_DIR)
        (o, e) = p.communicate()

        if o is not None:
            sys.stdout.write(o)
            sys.stdout.flush()

        if e is not None:
            sys.stderr.write(e)
            sys.stderr.flush()

    def env(self, message):
        env = ActionRunner.env(self, message)
        args = message.get('value', {}) if message else {}
        env['WHISK_INPUT'] = json.dumps(args)
        return env

if __name__ == '__main__':
    setRunner(Swift3Runner())
    main()

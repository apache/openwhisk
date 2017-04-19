"""Python proxy to run Swift action.

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
import glob
import sys
import subprocess
import codecs
import json
sys.path.append('../actionProxy')
from actionproxy import ActionRunner, main, setRunner  # noqa

SRC_EPILOGUE_FILE = '/swift3Action/epilogue.swift'
DEST_SCRIPT_FILE = '/swift3Action/spm-build/main.swift'
DEST_SCRIPT_DIR = '/swift3Action/spm-build'
DEST_BIN_FILE = '/swift3Action/spm-build/.build/release/Action'

BUILD_PROCESS = ['./swiftbuildandlink.sh']


class Swift3Runner(ActionRunner):

    def __init__(self):
        ActionRunner.__init__(self, DEST_SCRIPT_FILE, DEST_BIN_FILE)

    # remove pre-existing binary before receiving a new binary
    def preinit(self):
        try:
            os.remove(self.binary)
        except: pass

    def epilogue(self, init_message):
        # skip if executable already exists (was unzipped)
        if os.path.isfile(self.binary):
            return

        if 'main' in init_message:
            main_function = init_message['main']
        else:
            main_function = 'main'
        # make sure there is a main.swift file
        open(DEST_SCRIPT_FILE, 'a').close()

        with codecs.open(DEST_SCRIPT_FILE, 'a', 'utf-8') as fp:
            os.chdir(DEST_SCRIPT_DIR)
            for file in glob.glob("*.swift"):
                if file not in ["Package.swift", "main.swift", "_WhiskJSONUtils.swift", "_Whisk.swift"]:
                    with codecs.open(file, 'r', 'utf-8') as f:
                        fp.write(f.read())
            with codecs.open(SRC_EPILOGUE_FILE, 'r', 'utf-8') as ep:
                fp.write(ep.read())

            fp.write('_run_main(mainFunction: %s)\n' % main_function)

    def build(self, init_message):
        # short circuit the build, if there already exists a binary
        # from the zip file
        if os.path.isfile(self.binary):
            # file may not have executable permission, set it
            os.chmod(self.binary, 0o555)
            return

        p = subprocess.Popen(
            BUILD_PROCESS,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            cwd=DEST_SCRIPT_DIR)

        # run the process and wait until it completes.
        # stdout/stderr will not be None because we passed PIPEs to Popen
        (o, e) = p.communicate()

        # stdout/stderr may be either text or bytes, depending on Python
        # version, so if bytes, decode to text. Note that in Python 2
        # a string will match both types; so also skip decoding in that case
        if isinstance(o, bytes) and not isinstance(o, str):
            o = o.decode('utf-8')
        if isinstance(e, bytes) and not isinstance(e, str):
            e = e.decode('utf-8')

        if o:
            sys.stdout.write(o)
            sys.stdout.flush()

        if e:
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

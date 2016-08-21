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

import sys
import os
import stat
import json
import subprocess
import codecs
import flask
from gevent.wsgi import WSGIServer

class ActionRunner:

    LOG_SENTINEL = 'XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX'

    # initializes the runner
    # @param source the path where the source code will be located (if any)
    # @param binary the path where the binary wil be located (may be the same as source code path)
    def __init__(self, source = None, binary = None):
        defaultBinary = '/action/exec'
        self.source = source if source else defaultBinary
        self.binary = binary if binary else defaultBinary

    # extracts from the JSON object message a 'code' property and
    # writes it to the <source> path. The source code may have an
    # an optional <epilogue>. The source code is subsequently built
    # to produce the <binary> that is executed during <run>.
    # @param message is a JSON object, should contain 'code'
    # @return True iff binary exists and is executable
    def init(self, message):
        if 'code' in message:
            with codecs.open(self.source, 'w', 'utf-8') as fp:
                fp.write(str(message['code']))
                # write source epilogue if any
                self.epilogue(fp)
            try:
                # build the source
                self.build()
            except Exception:
                None  # do nothing, verify will signal failure if binary not executable
        # verify the binary exists and is executable
        return self.verify()

    # optionally appends source to the loaded code during <init>
    # @param fp the file stream writer
    def epilogue(self, fp):
        return

    # optionally builds the source code loaded during <init> into an executable
    def build(self):
        return

    # @return True iff binary exists and is executable, False otherwise
    def verify(self):
        return (os.path.isfile(self.binary) and os.access(self.binary, os.X_OK))

    # constructs an environment for the action to run in
    # @param message is a JSON object received from invoker (should contain 'value' and 'authKey')
    # @return an environment dictionary for the action process
    def env(self, message):
        # make sure to include all the env vars passed in by the invoker
        env = os.environ
        if 'authKey' in message:
            env['AUTH_KEY'] = message['authKey']
        return env

    # runs the action, called iff self.verify() is True.
    # @param args is a JSON object representing the input to the action
    # @param env is the environment for the action to run in (defined edge host, auth key)
    # return JSON object result of running the action or an error dictionary if action failed
    def run(self, args, env):
        def error(msg):
            # fall through (exception and else case are handled the same way)
            sys.stdout.write('%s\n' % msg)
            return (502, { 'error': 'The action did not return a dictionary.'})

        try:
            input = json.dumps(args)
            p = subprocess.Popen(
                [ self.binary, input ],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                env=env)
        except Exception as e:
            return error(e)

        # run the process and wait until it completes.
        (o, e) = p.communicate()

        if o is not None:
            process_output_lines = o.strip().split('\n')
            last_line = process_output_lines[-1]
            for line in process_output_lines[:-1]:
                sys.stdout.write('%s\n' % line)
        else:
            last_line = '{}'

        if e is not None:
            sys.stderr.write(e)

        try:
            json_output = json.loads(last_line)
            if isinstance(json_output, dict):
                return (200, json_output)
            else:
                return error(last_line)
        except Exception:
            return error(last_line)

proxy = flask.Flask(__name__)
proxy.debug = False
runner = None

def setRunner(r):
    global runner
    runner = r

@proxy.route('/init', methods=['POST'])
def init():
    message = flask.request.get_json(force=True, silent=True)
    if message and not isinstance(message, dict):
        flask.abort(404)
    else:
        value = message.get('value', {}) if message else {}

    if not isinstance(value, dict):
        flask.abort(404)

    try:
        status = runner.init(value)
    except Exception as e:
        status = False

    if status is True:
        return ('OK', 200)
    else:
        response = flask.jsonify({'error': 'The action failed to generate or locate a binary. See logs for details.' })
        response.status_code = 502
        return response

@proxy.route('/run', methods=['POST'])
def run():
    def complete(response):
        # Add sentinel to stdout/stderr
        sys.stdout.write('%s\n' % ActionRunner.LOG_SENTINEL)
        sys.stdout.flush()
        sys.stderr.write('%s\n' % ActionRunner.LOG_SENTINEL)
        sys.stderr.flush()
        return response

    def error():
        response = flask.jsonify({'error': 'The action did not receive a dictionary as an argument.' })
        response.status_code = 404
        return complete(response)

    message = flask.request.get_json(force=True, silent=True)
    if message and not isinstance(message, dict):
        return error()
    else:
        args = message.get('value', {}) if message else {}
        if not isinstance(args, dict):
            return error()

    if runner.verify():
        try:
            (code, result) = runner.run(args, runner.env(message if message else {}))
            response = flask.jsonify(result)
            response.status_code = code
        except Exception as e:
            response = flask.jsonify({'error': 'Internal error.' })
            response.status_code = 500
    else:
        response = flask.jsonify({'error': 'The action failed to locate a binary. See logs for details.' })
        response.status_code = 502
    return complete(response)

def main():
    port = int(os.getenv('FLASK_PROXY_PORT', 8080))
    server = WSGIServer(('', port), proxy, log=None)
    server.serve_forever()

if __name__ == '__main__':
    setRunner(ActionRunner())
    main()

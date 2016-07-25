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
import json
import subprocess
import codecs
import traceback
import flask
from gevent.wsgi import WSGIServer

proxy = flask.Flask(__name__)
proxy.debug = False

@proxy.route("/init", methods=['POST'])
def init():
    flask.g = None
    payload = flask.request.get_json(force=True,silent=True)
    if not payload or not isinstance(payload, dict):
        flask.abort(403)

    message = payload.get("value", {})
    if "code" in message:
        # store the compiled code
        try:
            flask.g = compile(message["code"], filename = 'action', mode = 'exec')
        except Exception:
            flask.g = None
            traceback.print_exc(file = sys.stderr, limit = 0)
            sys.stderr.flush()
            response = flask.jsonify({"error": "The action failed to compile. See logs for details." })
            response.status_code = 502
            return response
        return ('OK', 200)
    else:
        flask.abort(403)

@proxy.route("/run", methods=['POST'])
def run():
    message = flask.request.get_json(force=True,silent=True)

    if not message or not isinstance(message, dict):
        flask.abort(403)

    if not "value" in message:
        flask.abort(403)

    value = message["value"]

    if not isinstance(value, dict):
        flask.abort(403)

    if flask.g is None:
        # no code to execute
        response = flask.jsonify({"error": "No code to execute (compilation failed). See logs for details." })
        response.status_code = 502
        return response

    # initialize the namespace for the execution
    namespace = {}
    result = None
    try:
        namespace['param'] = value
        exec(flask.g, namespace)
        exec("fun = main(param)", namespace)
        result = namespace['fun']
    except Exception:
        traceback.print_exc(file = sys.stderr)
    sys.stdout.flush()
    sys.stderr.flush()
    if result and isinstance(result, dict):
        response = flask.jsonify(result)
        response.status_code = 200
        return response
    else:
        response = flask.jsonify({"error": "The action did not return a dictionary and returned this instead '%s'." % result })
        response.status_code = 502
        return response


# start server in a forever loop
if __name__ == "__main__":
    PORT = int(os.getenv("FLASK_PROXY_PORT", 8080))
    server = WSGIServer(('', PORT), proxy, log=None)
    server.serve_forever()

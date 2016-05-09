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

import flask

from gevent.wsgi import WSGIServer

proxy = flask.Flask(__name__)
proxy.debug = False

SRC_EPILOGUE_FILE = "./epilogue.swift"
DEST_SCRIPT_FILE = "/swiftAction/action.swift"
DEST_BIN_FILE = "/swiftAction/action"
BUILD_PROCESS = [ "swiftc", "-O", DEST_SCRIPT_FILE, "-o", DEST_BIN_FILE ]
# RUN_PROCESS = [ "swift", DEST_SCRIPT_FILE ]
RUN_PROCESS = [ DEST_BIN_FILE ]

@proxy.route("/init", methods=['POST'])
def init():
    message = flask.request.get_json(force=True,silent=True)
    if not message or not isinstance(message, dict):
        flask.abort(403)

    message = message.get("value", {})

    if "code" in message:
        # if the main function is wrapped in a struct, this code will strip the
        # struct declaration away, freeing the main function. If not, the result
        # will be the same as code
        codeWithoutStruct = ""

        with codecs.open(DEST_SCRIPT_FILE, "w", "utf-8") as fp:
            strippedStruct = False
            code = str(message["code"])

            lines = code.splitlines()
            foundStruct = False
            removedStructDeclaration = False

            for currentLine in lines:
                strippedLine = currentLine.lstrip()

                if not foundStruct and strippedLine.startswith("func main"):
                    # short circuit out and stop trying to remove a struct envelope
                    codeWithoutStruct = code
                    break

                if removedStructDeclaration:
                    codeWithoutStruct += currentLine + "\n"

                if not foundStruct:
                    # look for struct declaration
                    foundStruct = strippedLine.startswith("struct")

                if foundStruct and not removedStructDeclaration:
                    curlyBraceIndex = currentLine.find('{')

                    if curlyBraceIndex is not -1:
                        # scrape out everything up to and including the curly brace
                        codeWithoutStruct += currentLine[curlyBraceIndex + 1:]
                        removedStructDeclaration = True
                    # else:
                        # we have no use for this line. throw it away

            if removedStructDeclaration:
                # lop off the last }
                codeWithoutStruct = codeWithoutStruct[:codeWithoutStruct.rfind('}')]

            fp.write(codeWithoutStruct + "\n")

            with codecs.open(SRC_EPILOGUE_FILE, "r", "utf-8") as ep:
                fp.write(ep.read())

        p = subprocess.Popen(BUILD_PROCESS)

        (o,e) = p.communicate()

        if o is not None:
            sys.stdout.write(o)

        if e is not None:
            sys.stderr.write(e)

        if not (os.path.isfile(DEST_BIN_FILE) and os.access(DEST_BIN_FILE, os.X_OK)):
            response = flask.jsonify({"error": "the action failed to compile. See logs for details." })
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

    if not (os.path.isfile(DEST_BIN_FILE) and os.access(DEST_BIN_FILE, os.X_OK)):
        response = flask.jsonify({ "error": "the action failed to compile. See logs for details." })
        response.status_code = 502
        return response

    swift_env_in = { "WHISK_INPUT" : json.dumps(value) }

    p = subprocess.Popen(
        RUN_PROCESS,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        env=swift_env_in)

    # We run the Swift process, blocking until it completes.
    (o,e) = p.communicate()

    process_output = ""

    if o is not None:
        process_output_lines = o.strip().split("\n")

        last_line = process_output_lines[-1]

        for line in process_output_lines[:-1]:
            sys.stdout.write("%s\n" % line)

    if e is not None:
        sys.stderr.write(e)

    try:
        json_output = json.loads(last_line)
        if isinstance(json_output, dict):
            response = flask.jsonify(json_output)
            return response
        else:
            response = flask.jsonify({ "error": "the action did not return an object", "action_output": json_output })
            response.status_code = 502
            return response
    except Exception as e:
        # sys.stderr.write("Couldn't parse Swift script output as JSON: %s.\n" % last_line)
        # sys.stderr.write("%s\n%s\n" % (str(e),repr(e)))
        response = flask.jsonify({ "error": "the action did not return a valid result" })
        response.status_code = 502
        return response

if __name__ == "__main__":
    PORT = int(os.getenv("FLASK_PROXY_PORT", 8080))
    server = WSGIServer(('', PORT), proxy, log=None)
    server.serve_forever()

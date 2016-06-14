/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

var fs = require('fs');
var spawn = require('child_process').spawn;

function HelloService(logger) {

    var clientProg = "/blackbox/client/action";
    var server = undefined;

    /**
     * Starts the server.
     *
     * @param app express app
     */
    this.start = function start(app) {
        var self = this;
        server = app.listen(app.get('port'), function() {
            var host = server.address().address;
            var port = server.address().port;
            logger.info('[start] listening at http://%s:%s', host, port);
        });
    }

    /**
     * req.body = { }
     */
    this.initCode = function initCode(req, res) {
        // do nothing with init, simply respond OK
        // this can be adapted in the future to initialize
        // the external process
        res.status(200).send();
    }

    /**
     * req.body = { value: Object, meta { activationId : int } }
     */
    this.runCode = function runCode(req, res) {
        var meta = (req.body || {}).meta;
        var args = (req.body || {}).value;
        var proc = spawn(clientProg, [ JSON.stringify(args) ]);
        var output = ''

        proc.stdout.on('data', function(data) {
            // console.log('stdout: ' + data);
            output += data;
        });

        proc.stderr.on('data', function(data) {
            // console.log('stderr: ' + data);
            output += data;
        });

        proc.on('close', function(code) {
            // console.log('child process exited with code ' + code);
            var result = undefined;
            try {
                var result = JSON.parse(output);
                if (typeof result === 'object') {
                    res.status(200).json(result);
                } else {
                    console.error('action result is not an object:', output);
                    res.status(502).json({ error: 'result must be of type object but has type ' + typeof result });
                }
            } catch (e) {
                console.error('action result is not an object:', output);
                res.status(502).json({ error: 'result must be of type object but has type ' + typeof result });
            }
        });
    }
}

HelloService.getService = function(logger) {
    return new HelloService(logger);
}

module.exports = HelloService;

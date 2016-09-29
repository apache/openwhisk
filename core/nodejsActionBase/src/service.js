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

var NodeActionRunner = require('../runner');
var fs = require('fs');
var whisk = require('./whisk');

function NodeActionService(config, logger) {
    var Status = {
        ready: 'ready',
        starting: 'starting',
        running: 'running',
        stopped: 'stopped'
    }

    var status = Status.ready;
    var server = undefined;
    var userCodeRunner = undefined;

    function setStatus(newStatus) {
        if (status !== Status.stopped) {
            status = newStatus;
        }
    }

    /**
     * An ad-hoc format for the endpoints returning a Promise representing,
     * eventually, an HTTP response.
     *
     * The promised values (whether successful or not) have the form:
     * { code: int, response: object }
     *
     */
    function responseMessage (code, response) {
        return { code: code, response: response };
    }

    function errorMessage (code, errorMsg) {
        return responseMessage(code, { error: errorMsg });
    }

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
        });
    }

    /** Returns a promise of a response to the /init invocation.
     *
     *  req.body = { main: String, code: String, name: String }
     */
    this.initCode = function initCode(req) {
        if (status === Status.ready) {
            setStatus(Status.starting);

            var body = req.body || {};
            var message = body.value || {};

            if (message.main && message.code && typeof message.main === 'string' && typeof message.code === 'string') {
                return doInit(message).then(function (result) {
                    return responseMessage(200, { OK: true });
                }).catch(function (error) {
                    // this writes to the activation logs visible to the user
                    console.error('Error during initialization:', error);
                    var errStr = String(error.stack);
                    return Promise.reject(errorMessage(502, "Initialization has failed due to: " + errStr));
                });
            } else {
                setStatus(Status.ready);
                return Promise.reject(errorMessage(500, "Missing main/no code to execute."));
            }
        } else {
            return Promise.reject(errorMessage(502, "Internal system error: system not ready."));
        }
    }

    /**
     * Returns a promise of a response to the /exec invocation.
     * Note that the promise is failed if and only if there was an unhandled error
     * (the user code threw an exception, or our proxy had an internal error).
     * Actions returning { error: ... } are modeled as a Promise successful resolution.
     *
     * req.body = { value: Object, meta { activationId : int } }
     */
    this.runCode = function runCode(req) {
        if (status === Status.ready) {
            setStatus(Status.running);

            return doRun(req).then(function (result) {
                setStatus(Status.ready);

                if (typeof result !== "object") {
                    console.error('Result must be of type object but has type "' + typeof result + '":', result);
                    return errorMessage(502, "The action did not return a dictionary.");
                } else {
                    return responseMessage(200, result);
                }
            }).catch(function (error) {
                setStatus(Status.ready);

                return Promise.reject(errorMessage(500, "An error has occurred: " + error));
            });
        } else {
            logger.info('[runCode]', 'cannot schedule runCode due to status', status);
            return Promise.reject(errorMessage(500, "Internal system error: container not ready."));
        }
    }

    function doInit(message) {
        var context = newWhiskContext(config, logger);

        userCodeRunner = new NodeActionRunner(context);

        return userCodeRunner.init(message).then(function (result) {
            setStatus(Status.ready);
            // 'true' has no particular meaning here. The fact that the promise
            // is resolved successfully in itself carries the intended message
            // that initialization succeeded.
            return true;
        }).catch(function (error) {
            writeMarkers();
            setStatus(Status.stopped);
            return Promise.reject(error);
        });
    }

    function doRun(req) {
        var ids = (req.body || {}).meta;
        var args = (req.body || {}).value;
        var authKey = (req.body || {}).authKey;
        userCodeRunner.whisk.setAuthKey(authKey)

        return userCodeRunner.run(args).then(function(response) {
            writeMarkers();
            return response;
        }).catch(function (error) {
            console.error(error);
            writeMarkers();
            return Promise.reject(error);
        });
    }

    function writeMarkers() {
        console.log('XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX');
        console.error('XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX');
    }
}

function newWhiskContext(config, logger) {
    var apihost = undefined;

    if (config.edgeHost) {
        var edgeHostParts = config.edgeHost.split(':');
        var protocol = (edgeHostParts.length >= 2  &&  edgeHostParts[1] == '443') ? 'https' : 'http';
        apihost = protocol + '://' + config.edgeHost;
    }

    return new whisk(apihost, logger);
}

NodeActionService.getService = function(config, logger) {
    return new NodeActionService(config, logger);
}

module.exports = NodeActionService;

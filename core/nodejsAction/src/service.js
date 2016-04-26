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
var yauzl = require('yauzl');
var whisk = require('./whisk');

// There should be no calls to console.log. Use rawLog.
function NodeActionService(config, rawLog, logger) {

    var Status = {
        ready: 'ready',
        starting: 'starting',
        running: 'running',
        stopped: 'stopped'
    }

    var status = Status.ready;
    var server = undefined;
    var userScript = undefined;

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
     * req.body = { main: String, code: String, name: String }
     */
    this.initCode = function initCode(req, res) {
        if (status === Status.ready) {
            setStatus(Status.starting);

            try {
                var body = req.body || {};
                var message = body.value || {};
                logger.info('[initCode]', body);

                if (message.main && message.code && typeof message.main === 'string' && typeof message.code === 'string') {
                    if (!message.lib) {
                        var success = doInit(message);
                        if (success)
                            res.status(200).send();
                        else {
                            console.log('Error during initialization:', userScript.initError);
                            var errStr = String(userScript.initError.stack)
                            res.status(400).json({ msg: 'Error: Initialization has failed due to: ' + errStr });
                        }
                    } else {
                        res.status(500).json({ error: "'lib' option not supported." });
                    }
                }
            } catch (e) {
                logger.error('[initCode]', 'exception', e);
                shutdown(res, 500, { msg: 'exception in server ' + String(e) });
            }
        } else res.status(409).send();
    }

    /**
     * req.body = { value: Object, meta { activationId : int } }
     */
    this.runCode = function runCode(req, res) {
        if (status === Status.ready) {
            setStatus(Status.running);
            doRun(req, function(result, errMsg) {
                setStatus(Status.ready);
                if (result) {
                    // it is an error if the value is defined but not of type object
                    if (typeof result !== 'object') {
                        res.status(502).json({ error: 'result must be of type object but has type ' + typeof result });
                    } else {
                        res.status(200).json(result);
                    }
                } else {
                    res.status(500).json({ error: "An error has occurred: " + errMsg });
                }
            });
        } else if (status === Status.starting || status === Status.running) {
            logger.info('[runCode]', 're-scheduling runCode due to status',  status);
            setTimeout(function() { runCode(req, res); }, 1); // Node can achieve 1 ms
        } else {
            logger.info('[runCode]', 'cannot schedule runCode due to status', status);
            res.status(500).json({});
        }
    }

    function shutdown(res, code, msg) {
        status = Status.stopped
        logger.info('[setStatus]', 'stopping now');
        res.status(code).json(msg);
        server.close();
    }

    function setStatus(newStatus) {
        if (status !== Status.stopped) {
            status = newStatus;
        }
    }

    function doInit(message) {
        var context = newWhiskContext(config, logger);
        userScript = new NodeActionRunner(message, context, rawLog);
        if (typeof userScript.userScriptMain === 'function') {
            setStatus(Status.ready);
            logger.info('[doInit]', 'initCode initialized:', userScript.userScriptName);
            return true;
        } else {
            logger.error('[doInit]', 'initCode failed to initialize');
            setStatus(Status.stopped);
            return false;
        }
    }

    function doRun(req, next) {
        try {
            logger.info('[doRun]', 'invoking with args', req.body)
            var ids = (req.body || {}).meta;
            var args = (req.body || {}).value;
            var authKey = (req.body || {}).authKey;
            userScript.whisk.setAuthKey(authKey)
            userScript.run(args, function(response) {
                rawLog.log('XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX')
                rawLog.error('XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX')
                logger.info('[doRun]', 'response is', response.result);
                logger.info('[usage]', userScript.userScriptName,
                            ': activationId = ', (ids || {}).activationId,
                            '  Duration = ', response.duration+'ms',
                            '  MemUsage = ', response.memusage);
                next(response.result, undefined);
            });
        } catch (e) {
            logger.error('[doRun]', 'runCode exception', e);
            next(false, e);
        }
    }
}

function newWhiskContext(config, logger) {
    var apihost = undefined;

    if (config.edgeHost) {
        var edgeHostParts = config.edgeHost.split(':');
        var protocol = (edgeHostParts.length >= 2  &&  edgeHostParts[1] == '443') ? 'https' : 'http';
        apihost = protocol + '://' + config.edgeHost;
    }

    return new whisk(apihost, config.whiskVersion, logger);
}

NodeActionService.getService = function(config, rawLog, logger) {
    return new NodeActionService(config, rawLog, logger);
}

module.exports = NodeActionService;

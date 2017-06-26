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

var config = {
        'port': 8080,
        'apiHost': process.env.__OW_API_HOST
};

var bodyParser = require('body-parser');
var express    = require('express');

var app = express();

var logger  = require('./src/logger').getLogger('logs/nodejsaction.log', 'nodejsAction');

/**
 * instantiate an object which handles REST calls from the Invoker
 */
var service = require('./src/service').getService(config, logger);

app.set('port', config.port);
app.use(bodyParser.json({ limit: "48mb" }));

app.post('/init', wrapEndpoint(service.initCode));
app.post('/run',  wrapEndpoint(service.runCode));

app.use(function(err, req, res, next) {
    console.error(err.stack);
    res.status(500).json({ error: "Bad request." });
  });

service.start(app);

/**
 * Wraps an endpoint written to return a Promise into an express endpoint,
 * producing the appropriate HTTP response and closing it for all controlable
 * failure modes.
 *
 * The expected signature for the promise value (both completed and failed)
 * is { code: int, response: object }.
 *
 * @param ep a request=>promise function
 * @returns an express endpoint handler
 */
function wrapEndpoint(ep) {
    return function (req, res) {
        try {
            ep(req).then(function (result) {
                res.status(result.code).json(result.response);
            }).catch(function (error) {
                if (typeof error.code === "number" && typeof error.response !== "undefined") {
                    res.status(error.code).json(error.response);
                } else {
                    logger.error("[wrapEndpoint]", "invalid errored promise", JSON.stringify(error));
                    res.status(500).json({ error: "Internal error." });
                }
            });
        } catch (e) {
            // This should not happen, as the contract for the endpoints is to
            // never (externally) throw, and wrap failures in the promise instead,
            // but, as they say, better safe than sorry.
            logger.error("[wrapEndpoint]", "exception caught", e);

            res.status(500).json({ error: "Internal error (exception)." });
        }
    }
}

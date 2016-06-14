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

var util       = require('util');
var bodyParser = require('body-parser');
var express    = require('express');
var app        = express();
var logger     = require('./src/logger').getLogger('logs/hello.log', 'hello');
var port       = 8080


/**
 * instantiates an object which handles REST calls from the Invoker
 */
var service = require('./src/service').getService(logger);

app.set('port', port);
app.use(bodyParser.json());
app.post('/init', safeEndpoint(service.initCode));
app.post('/run',  safeEndpoint(service.runCode));
app.use(function(err, req, res, next) {
    console.error(err.stack);
    res.status(500).send('bad request');
  });
logger.info('Starting a blackbox service');
service.start(app);

/**
 * Wraps an endpoint with a try-catch to catch errors and close off http
 * responses correctly in case of errors.
 *
 * @param ep function endpoint
 * @return new function closure with internal try-catch
 */
function safeEndpoint(ep) {
    return function safeEndpoint(req, res, next) {
        try {
            ep(req, res, next);
        } catch (e) {
            logger.error('[safeEndpoint]', 'exception caught', e);
            res.json({ error : 'internal error' });
        }
    };
}



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

module.exports = Whisk;

var btoa = require('btoa');
var request = require('request');

/**
 * Javascript SDK for whisk actions. This module is imported as 'whisk' in user
 * javascript actions.
 *
 * @param apihost the API end point 'https://host[:port]'
 * @param apikey  the API key to use for whisk API calls,
 * @param version the whisk version, optional
 * @param logger an optional log4js logger (with info() method)
 */
function Whisk(apihost, version, logger) {

    this.apikey = "uninit_apikey";

    var asyncSentinel = new function Async(){ async: true; };

    /**
     * Parses a (possibly fully qualified) resource name into
     * namespace and name components.
     *
     * @param qname A (possibly fully qualified) resource name.
     * @return An object with 'namespace' and 'name' properties.
     */
    var parseQName = function(qname) {
        var parsed = {};
        var delimiter = '/';
        var defaultNamespace = '_';
        if (qname && qname.charAt(0) === delimiter) {
            var parts = qname.split(delimiter);
            parsed.namespace = parts[1];
            parsed.name = parts.length > 2 ? parts.slice(2).join(delimiter) : '';
        } else {
            parsed.namespace = defaultNamespace;
            parsed.name = qname;
        }
        return parsed;
    }

    /**
     * Gets a string naming the whisk version of the current deployment.
     */
    this.getWhiskVersion = function() {
        return version;
    }

    /**
     * Gets the authorization key under which this action is running.
     */
    this.getAuthKey = function() {
        return this.apikey;
    }

    /**
     * Gets the authorization key under which this action is running.
     */
    this.setAuthKey = function(key) {
        this.apikey = key;
    }

    /**
     * Invokes a whisk action.
     *
     * @param name The fully qualified name of action to invoke.
     * @param parameters An optional invoke POST body (the argument for the action); must be a JSON object.
     * @param apikey An optional authorization key to invoke action with; if not defined, defaults to getAuthKey().
     * @param blocking An optional parameter, blocking invoke iff true (default is false).
     * @param next An optional callback for invocation response as a function(error, activation).
     *             Error is a truthy iff invoke failed, usually a string describing error.
     *             Activation, if defined, is an object { result: JSON object, activationId: String }
     *             where result is undefined if the invoke is non-blocking. Activation may be defined
     *             when error is not a falsey depending on invoke failure mode.
     */
    this.invoke = function(params) {
        var args = params || {};
        var action = parseQName(args.name || '');
        var parameters = args.parameters || {};
        var key = args.apikey || this.getAuthKey();
        var blocking = args.blocking || false;
        var next = typeof args.next === 'function' ? args.next : undefined;

        var packet = {
            url: apihost + '/api/v1/namespaces/' + action.namespace + '/actions/' + action.name + '?blocking='+ blocking,
            parameters: parameters,
            auth: key
        };

        logger && logger.info('[whisk]', '[invoke]', 'invoking', action);
        // The result of a successful blocking invoke is an activation record
        // { activationId, response: { result } } among other properties.
        //
        // The result of a successful non-blocking invoke is an activation record
        // { activationId }.
        //
        // Project the appropriate value based on invoke mode and HTTP statusCode
        // where success is 200, and anything else is a failure.
        post(packet, logger, function(error, response, body) {
            logger && logger.info('[whisk]', '[invoke]', 'response body', body);

            var activation = undefined;

            error = error || undefined;
            response = response || {};
            body = body || {};

            if (response.statusCode == 200 || response.statusCode == 202) {
                activation = {
                   activationId: body.activationId, // id always present
                   result: (body.response || {}).result // may not exist if non-blocking
                };
            } else if (!error) {
                // activation failed, set error to API host error response.
                error = body.error + ' (' + body.errorCode + ')';
            }

            if (next) {
                logger && logger.info('[whisk]', '[invoke]', 'next', error, activation);
                next(error, activation);
            }
        });
    }

    /**
     * Fires a whisk trigger.
     *
     * @param name The fully qualified name of trigger to invoke.
     * @param parameters An optional trigger POST body (the argument for the trigger); must be a JSON object.
     * @param apikey An optional authorization key to fire trigger with; if not defined, defaults to getAuthKey().
     * @param next An optional callback for trigger fire response as a function(error, activation).
     *             Error is a truthy iff trigger fire failed, usually a string describing error.
     *             Activation, if defined, is an object { activationId: String }.
     */
    this.trigger = function(params) {
        var args = params || {};
        var event = parseQName(args.name || '');
        var parameters = args.parameters || {};
        var key = args.apikey || this.getAuthKey();
        var next = typeof args.next === 'function' ? args.next : undefined;

        var packet = {
            url: apihost + '/api/v1/namespaces/' + event.namespace + '/triggers/' + event.name,
            parameters: parameters,
            auth: key
        };

        logger && logger.info('[whisk]', '[trigger]', 'firing', event);
        // The result of a successful trigger is an activation record
        // { activationId } among other properties.
        //
        // Project the appropriate value based on HTTP statusCode
        // where success is 200, and anything else is a failure.
        post(packet, logger, function(error, response, body) {
            logger && logger.info('[whisk]', '[trigger]', 'response body', body);

            var activation = undefined;

            error = error || undefined;
            response = response || {};
            body = body || {};

            if (response.statusCode == 200) {
                activation = { activation: body.activationId }; // id always present
            } else if (!error) {
                // activation failed, set error to API host error response.
                error = body.error + ' (' + body.errorCode + ')';
            }

            if (next) {
                logger && logger.info('[whisk]', '[trigger]', 'next', error, activation);
                next(error, activation);
            }
        });
    }

    /**
     * The action activation is terminated after this call. For internal use only.
     * Call `whisk.done` or `whisk.error` from your action instead.
     */
    this._terminate = function(response) {
        return response;
    }

    /**
     * Sets the activation result. The result of the activation must be of type
     * object (if defined).
     *
     * It is an error to call this method more than once.
     * The action activation is considered complete after this call.
     *
     * @param value an optional value to set as the result of the activation. Not
     * setting a value is equivalent to setting the empty object.
     */
    this.done = function(value) {
        var response = value;

        // All non-truthy responses are converted to the empty object.
        if (!response) {
            response = {};
        }

        logger && logger.info('[whisk]', 'done()', response);
        return this._terminate(response);
    }

    /**
     * Returns an activation describing the error if an error value is defined, or
     * describing a generic error otherwise. The presence of the 'error' field in the
     * activation response marks it as having failed. Calling this method with a defined
     * value is equivalent to Whisk.done({ error: value }), and otherwise to
     * Whisk.done({ error: <generic error message> }).
     *
     * It is an error to call this method more than once.
     * The action activation is considered complete after this call.
     *
     * @param value an optional error value to set as the error value of the activation. If
     * the value is not set, a generic error message is returned instead.
     */
    this.error = function(value) {
        var errorValue = value === undefined ? 'an error has occurred' : value;
        var response = { error: errorValue };
        logger && logger.info('[whisk]', 'error()', response);
        return this._terminate(response);
    }

    /** A sentinel to denote asynchronous activation. */
    this.async = function() {
        return asyncSentinel;
    }
}

/**
 * Issues an HTTP POST.
 *
 * @param packet The object with post parameters.
 * @param   packet.url The url to POST to.
 * @param   packet.auth The basicauth credentials (optional).
 * @param   packet.parameters The POST body as JSON object (optional).
 * @param logger Logging object.
 * @param next An optional callback for POST response as a function(error, response, body).
 */
function post(packet, logger, next) {
    logger && logger.info('[whisk]', 'POST to url', packet.url);
    logger && logger.info('[whisk]', 'send parameters', packet.parameters);

    var options = {
        url: packet.url,
        agentOptions: {rejectUnauthorized: false},
        headers: {
            'Content-Type': 'application/json',
            'Authorization': 'Basic ' + btoa(packet.auth),
        },
        json: packet.parameters,
    };

    request.post(options, function(error, response, body) {
        logger && logger.info('[whisk]', 'post status:', response ? response.statusCode : undefined);
        // print the error to console.error to help post-mortem debugging
        // and inform the user if for example they neglected to check for error
        error  && console.log('Warning: whisk activation request failed with the following error', error);
        if (next) next(error, response, body);
    });
}

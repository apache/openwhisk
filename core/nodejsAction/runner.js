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

/**
 * Object which encapsulates a first-class function, the user code for
 * an action.
 *
 * This file (runner.js) must currently live in root directory for nodeJsAction.
 * See issue #102 for a discussion.
 */
var util  = require('util');

function NodeActionRunner(message, whisk) {
    this.userScriptName = getname(message.name);
    this.userScriptMain = undefined;
    this.initError = undefined;

    try {
        eval(message.code);
        this.userScriptMain = eval(message.main);
    } catch (e) {
        this.initError = e;
        return;
    }

    var hrStart = undefined;
    var callback = { };

    function closeAndReturn(result) {
        var hrEnd = process.hrtime();

        if (typeof callback.closedConnection == 'undefined') {
            callback.closedConnection = true;
            callback.next({
                result  : result,
                duration: duration(hrStart, hrEnd),
                memusage: util.inspect(process.memoryUsage())
            });
        } else {
            // There is no 'else': we can't close the connection more than once
            // (which is what callback.next eventually does). We should warn the
            // user if (s)he is trying to do just that, but that warning takes
            // place in the call to (overwritten) _terminate.
        }
    }

    this.whisk = modWhisk(whisk, callback, closeAndReturn);

    // This throws an exception if the userScript does. This lets the service
    // distinguish between willful application errors and inadvertent ones.
    this.run = function(args, next) {
        callback.done = undefined;
        callback.closedConnection = undefined;
        callback.next = next;

        hrStart = process.hrtime(); // sec and nanosec

        var result = this.userScriptMain(args);

        if (result !== this.whisk.async()) {
            // This happens, e.g. if you just have "return;"
            if (typeof result == 'undefined') {
                result = {};
            }
            closeAndReturn(result);
        }
    }
}

/**
 * override whisk._terminate to create a continuation and log occurrences of
 * calling whisk._terminate more than once -- a trampoline pattern.
 *
 * @param whisk is an instance of Whisk (see whisk.js) that is in
 * the scope of the user code to run.  This encapsulates the whisk
 * SDK available to user code.
 *
 * @param callback a reference to an empty object which will record if
 *  whisk.done is called; when whisk._terminate is called the first time, a
 *  property “done” is added to callback object and the continuation
 *  function is called;  if whisk._terminate is called more than once, we check
 *  if the property done already exists and it should, and log an error message
 *
 * @param closeAndReturn a continuation function to call on whisk._terminate
 * (receives arguments passed to Whisk.done or Whisk.error namely a result object)
 */
function modWhisk(whisk, callback, closeAndReturn) {
    whisk._terminate = function(r) {
        if (callback.done === undefined) {
            callback.done = true;
            closeAndReturn(r);
        } else {
            // The warning doesn't mention _terminate because users aren't supposed to
            // call it directly.
            console.log('Warning: whisk.done() or whisk.error() called more than once.');
        }
    }
    return whisk;
}

function duration(start, end) {
    var elapsedMicro = (end[0] - start[0]) * 1000000 + Math.round((end[1] - start[1]) / 1000);
    return elapsedMicro / 1000.0;
}

function getname(name) {
    name = typeof name === 'string' ? name.trim() : '';
    name = name != '' ? name : 'Anonymous User Action';
    return name;
}

module.exports = NodeActionRunner;

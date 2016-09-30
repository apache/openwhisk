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
 */
var util = require('util');
var child_process = require('child_process');
var fs = require('fs');
var path = require('path');

function NodeActionRunner(whisk) {
    // Use this ref inside lambdas etc.
    var thisRunner = this;

    this.userScriptName = undefined;
    this.userScriptMain = undefined;

    // This structure is reset for every action invocation. It contains two fields:
    //   - completed; indicating whether the action has already signaled completion
    //   - next; a callback to be invoked with the result of the action.
    // Note that { error: ... } results are still results.
    var callback = {
        completed : undefined,
        next      : function (result) { return; }
    };

    this.whisk = modWhisk(whisk, callback);

    this.init = function(message) {
        // Determining a sensible name for the action.
        var name = typeof message.name === 'string' ? message.name.trim() : '';
        name = name !== '' ? name : 'Anonymous User Action';

        this.userScriptName = name;

        function assertMainIsFunction() {
            if (typeof thisRunner.userScriptMain !== 'function') {
                throw "Action entrypoint '" + message.main + "' is not a function.";
            }
        }

        // Loading the user code.
        if(message.binary) {
            // The code is a base64-encoded zip file.
            return unzipInTmpDir(message.code).then(function (moduleDir) {
                try {
                    thisRunner.userScriptMain = eval('require("' + moduleDir + '").' + message.main);
                    assertMainIsFunction();
                    // The value 'true' has no special meaning here;
                    // the successful state is fully reflected in the
                    // successful resolution of the promise.
                    return true;
                } catch (e) {
                    return Promise.reject(e);
                }
            }).catch(function (error) {
                return Promise.reject(error);
            });
        } else {
            // The code is a plain old JS file.
            try {
                eval(message.code);
                thisRunner.userScriptMain = eval(message.main);
                assertMainIsFunction()
                // See comment above about 'true'; it has no specific meaning.
                return Promise.resolve(true);
            } catch (e) {
                return Promise.reject(e);
            }
        }
    }

    // Returns a Promise with the result of the user code invocation.
    // The Promise is rejected iff the user code throws.
    this.run = function(args) {
        return new Promise(
            function (resolve, reject) {
                callback.completed = undefined;
                callback.next = resolve;

                try {
                    var result = thisRunner.userScriptMain(args);
                } catch (e) {
                    reject(e);
                }

                if (result !== thisRunner.whisk.async()) {
                    // This branch handles all direct (non-async) returns, as well
                    // as returned Promises.

                    // Non-promises/undefined instantly resolve.
                    Promise.resolve(result).then(function (resolvedResult) {
                        // This happens, e.g. if you just have "return;"
                        if (typeof resolvedResult === "undefined") {
                            resolvedResult = {};
                        }
                        resolve(resolvedResult);
                    }).catch(function (error) {
                        // A rejected Promise from the user code maps into a
                        // successful promise wrapping a whisk-encoded error.
                        resolve({ error: error });
                    });
                } else {
                    // Nothing to do in this 'else' branch. The user code returned the
                    // 'async' signal, indicating that it will call .done or .error.
                    // At that point, callback.next will be invoked, and the Promise will
                    // be completed.
                }
            }
        );
    };

    // Helper function to copy a base64-encoded zip file to a temporary location,
    // decompress it into temporary directory, and return the name of that directory.
    // Note that this makes heavy use of shell commands because:
    //   1) Node 0.12 doesn't have many of the useful fs functions.
    //   2) We know in which environment we're running.
    function unzipInTmpDir(base64) {
        var mkTempCmd = "mktemp -d XXXXXXXX";
        return exec(mkTempCmd).then(function (tmpDir1) {
            return new Promise(
                function (resolve, reject) {
                    var zipFile = path.join(tmpDir1, "action.zip");
                    fs.writeFile(zipFile, base64, "base64", function (err) {
                        if(err) {
                            reject("There was an error reading the action archive.");
                        }
                        resolve(zipFile);
                    });
                }
            );
        }).then(function (zipFile) {
            return exec(mkTempCmd).then(function (tmpDir2) {
                return exec("unzip " + zipFile + " -d " + tmpDir2).then(function (res) {
                   return path.resolve(tmpDir2);
                }).catch(function (error) {
                   return Promise.reject("There was an error uncompressing the action archive.");
                });
            });
        });
    }

    // Helper function to run shell commands.
    function exec(cmd) {
        return new Promise(
            function (resolve, reject) {
                child_process.exec(cmd, function (error, stdout, stderr) {
                    if(error) {
                        reject(stderr.trim());
                    } else {
                        resolve(stdout.trim());
                    }
                });
            }
        );
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
 * @param callback a reference to an object matching the schema of
 * `callback` above. It includes a continuation callback and a field marking
 * whether it was invoked already.
 *
 */
function modWhisk(whisk, callback) {
    whisk._terminate = function(r) {
        if (callback.completed === undefined) {
            callback.completed = true;
            callback.next(r);
        } else {
            // The warning doesn't mention _terminate because users aren't supposed to
            // call it directly.
            console.log('Warning: whisk.done() or whisk.error() called more than once.');
        }
    };
    return whisk;
}

module.exports = NodeActionRunner;

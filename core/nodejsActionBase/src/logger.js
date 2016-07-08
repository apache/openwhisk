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

'use strict';

var fs = require('fs');
var path = require('path');
var log4js = require('log4js');

function Logger(filename) {
    this.filename = filename;
}

Logger.prototype.makeLogger = function makeLogger(category) {
    var dir = path.dirname(this.filename);
    if (dir && !fs.existsSync(dir)) {
        fs.mkdirSync(dir);
    }
    log4js.configure(loggerConfig(this.filename));
    var thelogger = log4js.getLogger(category);
    thelogger.shutdown = function(cb) {
        log4js.shutdown(cb);
    }
    return thelogger;
}

Logger.getLogger = function(filename, category) {
    return new Logger(filename).makeLogger(category);
}

function loggerConfig(filename) {
    return {
        appenders : [ {
            type : 'dateFile',
            filename : filename,
            pattern : "-yyyy-MM-dd",
            alwaysIncludePattern : true,
            layout : {
                type : 'pattern',
                pattern : "[%d{yyyy-MM-ddThh:mm:ss.SSSO}] [%h] [%p] [%c] %m"
            }
        }]
    };
}

module.exports = Logger;

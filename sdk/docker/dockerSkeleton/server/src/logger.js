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

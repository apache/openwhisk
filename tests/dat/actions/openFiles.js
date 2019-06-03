// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements; and to You under the Apache License, Version 2.0.

var fs = require("fs");

function main(params) {

    var numFiles = params.numFiles;
    var openFiles = [];
    var error = undefined;

    try {
        for (var i = 0; i < numFiles; i++) {
            var fh = fs.openSync("/dev/zero", "r");
            openFiles.push(fh);
        }
    } catch (err) {
        console.log("ERROR: opened files = ", openFiles.length);
        error = err;
    }

    console.log("opened files = ", openFiles.length);

    openFiles.forEach(function(fh) {
        fs.close(fh, (err) => {} );
    })

    if (error === undefined) {
        return {
            filesToOpen : numFiles,
            filesOpen : openFiles.length,
        }
    } else {
        return {
            error : {
                filesToOpen : numFiles,
                filesOpen : openFiles.length,
                message : error
            }
        }
    }
}

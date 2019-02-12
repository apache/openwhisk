// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements; and to You under the Apache License, Version 2.0.

function main(msg) {
    var hostToPing = msg.payload;
    console.log('Pinging to ' + hostToPing);

    var spawn = require('child_process').exec;
    var promise = new Promise(function(resolve, reject) {
        var child = spawn('ping -c 3 ' + hostToPing);

        var tmp = {stdout: "", stderr: "", code: 0};

        child.stdout.on('data', function (data) {
            tmp.stdout = tmp.stdout + data;
        });

        child.stderr.on('data', function (data) {
            tmp.stderr = tmp.stderr + data;
        });

        child.on('close', function (code) {
            tmp.code = code;
            console.log('code', tmp.code);
            console.log('stdout', tmp.stdout);
            console.log('stderr', tmp.stderr);
            resolve(tmp);
        });
    });

    return promise;
}

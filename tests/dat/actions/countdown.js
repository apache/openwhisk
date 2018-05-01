// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements; and to You under the Apache License, Version 2.0.

/**
 * An action that invokes itself recursively, programmatically using the whisk
 * Javascript API.
 */
var openwhisk = require('openwhisk')

function main(params) {
    var wsk = openwhisk({ignore_certs: true})

    var n = parseInt(params.n);
    console.log(n);
    if (n === 0) {
        console.log('Happy New Year!');
    } else if (n > 0) {
        return wsk.actions.invoke({
            actionName: process.env['__OW_ACTION_NAME'],
            params: { n: n - 1 }
        });
    }
}

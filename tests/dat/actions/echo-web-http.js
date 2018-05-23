// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements; and to You under the Apache License, Version 2.0.

function main(params) {
    var bodyobj = params || {};
    bodystr = JSON.stringify(bodyobj);
    return {
        statusCode: 200,
        headers: { 'Content-Type': 'application/json' },
        body: new Buffer(bodystr).toString('base64')
    };
}

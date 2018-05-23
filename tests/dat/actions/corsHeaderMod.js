// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements; and to You under the Apache License, Version 2.0.

function main() {
    return {
        headers: {
            "Access-Control-Allow-Origin": "Origin set from Web Action",
            "Access-Control-Allow-Headers": "Headers set from Web Action",
            "Access-Control-Allow-Methods": "Methods set from Web Action",
            "Location": "openwhisk.org",
            "Set-Cookie": "cookie-cookie-cookie"
        },
        statusCode: 200
    }
}

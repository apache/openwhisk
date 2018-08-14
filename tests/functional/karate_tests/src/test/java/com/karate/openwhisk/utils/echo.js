
function main(params) {
 // Licensed to the Apache Software Foundation (ASF) under one or more contributor
 // license agreements; and to You under the Apache License, Version 2.0.
    return {
        statusCode: 200,
        body: params,
        headers: {
            "Cache-Control": "max-age=60"
        }
    }
}


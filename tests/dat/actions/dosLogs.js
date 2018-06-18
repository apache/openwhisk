// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements; and to You under the Apache License, Version 2.0.

function main(msg) {
    // Each log line with 16 characters (new line character counts)
    var lines = msg.payload / 16 || 1;
    for(var i = 1; i <= lines; i++) {
        console.log("123456789abcdef");
    }
    return {msg: lines};
}

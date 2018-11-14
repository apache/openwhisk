// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements; and to You under the Apache License, Version 2.0.

let counter = 0;
let requestCount = undefined;
let interval = 100;
function main(args) {
    //args.warm == 1 => indicates a warmup activation (to avoid multiple containers when concurrent activations arrive at prewarm state)
    //args.requestCount == n => indicates number of activations needed to arrive before returning results to ANY of the pending activations
    if (args.warm == 1) {
        return {warm: 1};
    } else {
        requestCount = requestCount || args.requestCount;
        counter++;
        console.log("counter: "+counter + " requestCount: "+requestCount);
        return new Promise(function(resolve, reject) {
            setTimeout(function() {
                checkRequests(args, resolve, reject);
            }, interval);
        });
    }

}
function checkRequests(args, resolve, reject, elapsed) {
    let elapsedTime = elapsed||0;
    if (counter == requestCount) {
        const result = {msg: "Received " + counter + " activations."};
        console.log(result.msg);
        resolve(result);
    } else {
        if (elapsedTime > 30000) {
            reject("did not receive "+requestCount+" activations within 30s");
        } else {
            setTimeout(function() {
                checkRequests(args, resolve, reject, elapsedTime+interval);
            }, interval);
        }
    }
}
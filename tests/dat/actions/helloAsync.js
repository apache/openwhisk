// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements; and to You under the Apache License, Version 2.0.

/**
 * word count utility, coded as an asynchronous action for pedagogical
 * purposes
 */
function wc(params) {
    var str = params.payload;
    var words = str.split(" ");
    var count = words.length;
    console.log("The message '"+str+"' has", count, 'words');
    return {count: count};
}

function main(params) {
    return new Promise(function(resolve, reject) {
        setTimeout(function () {
            resolve(wc(params));
        }, 100);
    });
}

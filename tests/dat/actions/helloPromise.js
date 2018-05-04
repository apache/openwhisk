// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements; and to You under the Apache License, Version 2.0.

function main(args) {
    return new Promise(function(resolve, reject) {
        setTimeout(function() {
            resolve({
                done : true
            });
        }, 2000);
    })
}

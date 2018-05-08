// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements; and to You under the Apache License, Version 2.0.

// We expect this action to always result in errored activations.
function main(args) {
    return new Promise((resolve, reject) => {
        reject();
    });
}

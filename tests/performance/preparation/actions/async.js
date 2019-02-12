// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements; and to You under the Apache License, Version 2.0.

function main() {
  return new Promise(function (resolve, reject) {
    setTimeout(function () {
      resolve({done: true});
    }, 175);
  })
}

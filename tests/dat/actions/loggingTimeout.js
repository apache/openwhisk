// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements; and to You under the Apache License, Version 2.0.

// This action requires Node.js v8 or higher.

// This action prints log lines to stdout for the duration of durationMillis.
// The output is throttled by a delay of delayMillis between two log lines in
// order to keep the log small and to stay within the log size limit.

function sleep(ms) {
  return new Promise((resolve, reject) => {
    setTimeout(resolve, ms);
  })
}

async function doLog(startMillis, durationMillis, delayMillis) {
  var logLines = 0;
  while (new Date() - startMillis <= durationMillis) {
    logLines++;
    console.log('[' + logLines + '] The quick brown fox jumps over the lazy dog.');
    await sleep(delayMillis);
  }
  return logLines;
}

function main(args) {
  const durationMillis = args.durationMillis;
  const delayMillis = args.delayMillis;
  const startMillis = new Date();

  return new Promise((resolve, reject) => {
    doLog(startMillis, durationMillis, delayMillis).then(numLogLines => {
      const runtime = new Date() - startMillis;
      const message = "hello, I'm back after " + runtime +' ms and printed ' + numLogLines + ' log lines';
      console.log(message);
      resolve({ message : message });
    });
  });
}
// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements; and to You under the Apache License, Version 2.0.

// This action prints log lines to stdout for the duration of durationMillis.
// The output is throttled by a delay of delayMillis between two log lines in
// order to keep the log small and to stay within the log size limit.

function doLog(startMillis, durationMillis, delayMillis) {
  var lastLog = 0;
  var logLines = 0;
  while (true) {
     now = new Date();
     if (now - lastLog > delayMillis) {
        lastLog = now;
        logLines++;
        console.log('[' + logLines + '] The quick brown fox jumps over the lazy dog.');
     }
     if (now - startMillis >= durationMillis) {
        break;
     }
  }
  return logLines;
}


function main(args) {
   const durationMillis = args.durationMillis;
   const delayMillis = args.delayMillis;
   const startMillis = new Date();

   var numLogLines = doLog(startMillis, durationMillis, delayMillis);
   var totalMillis = new Date() - startMillis;
   const message = "hello, I'm back after " + totalMillis +' ms and printed ' + numLogLines + ' log lines';
   return { message : `${message}`};
}

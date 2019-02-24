// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements; and to You under the Apache License, Version 2.0.

// run test as action with wsk-CLI:
// wsk action invoke loadtest -b --param durationMillis 15000 --param logDeltaMillis 1000 --param taskName task_log
// wsk action invoke loadtest -b --param delayMillis 0 --param durationMillis 15000 --param logDeltaMillis 250 --param taskName task_cpu
// wsk action invoke loadtest -b --param delayMillis 0 --param durationMillis 10000 --param addMillis 5000 --param addMillisPercentage 50 --param logDeltaMillis 250 --param taskName task_cpu
// run test with nodejs from command-line
// node loadTestAction.js '{ "durationMillis":15000, "logDeltaMillis":1000, "taskName":"task_log", "taskParams":null }'
// node loadTestAction.js '{ "delayMillis":0, "durationMillis":15000, "logDeltaMillis":250, "taskName":"task_cpu", "taskParams":null }'
// node loadTestAction.js '{ "delayMillis":0, "durationMillis":15000, "addMillis": 5000, "addMillisPercentage": 50, "logDeltaMillis":250, "taskName":"task_cpu", "taskParams":null }'
/*
 Calls func(i, taskParams) in a loop for the duration of durationMillis, where i is a counter starting with 0.
 delayMillis determines the wait time between two consecutive calls of func(i, args).
 Additionally, log lines are output to STDIO if logDeltaMillis >= 0.
 One line is output to STDIO after the call of func(i, args) if the previous output of a log line
 is at least logDeltaMillis ago. Otherwise, logging is suppressed for that iteration.
 If logDeltaMillis < 0, logging is completely suppressed.
*/
function doWork(func, testArgs) {
  var i = 0;
  var start = new Date();
  var lastLog = 0;
  var logLines = 0;
  while (true) {
     if (func != null) {
        func(i, testArgs.taskParams);
     }
     i += 1;
     now = new Date();
     // logging: print a line to stdout after func terminates but not faster than every logDeltaMillis ms
     if (testArgs.logDeltaMillis >= 0 && now - lastLog > testArgs.logDeltaMillis) {
        lastLog = now;
        logLines++;
        console.log((new Date().toISOString()) + " [js] The quick brown fox jumps over the lazy dog. Jackdaws love my big sphinx of quartz.");
     }
     if (now - start >= testArgs.durationMillis) {
        break;
     }
  }
  return { iterations: i, logLines: logLines };
}
function task_cpu(testArgs) {
   var func = (i, params) => Math.atan2(Math.sqrt(i), Math.sin(i + Math.cos(i)) * Math.cos(i + Math.sin(i)));
   return doWork(func, testArgs);
}
function task_log(testArgs) {
   return doWork(undefined, testArgs);
}
var tasks = {
  task_cpu: task_cpu,
  task_log: task_log
}
function getArg(value, d) {
  if (value == undefined) {
    return d;
  }
  return value;
}
function getRandomInt(min, max) {
  return min + Math.floor(Math.random() * (max - min));
}
function main(args) {
   var startMillis = new Date();
   var testArgs = {
      delayMillis: getArg(args.delayMillis, 0), // sleep time between iterations
      durationMillis: getArg(args.durationMillis, 10000), // total duration of test (approx.)
      addMillis: getArg(args.addMillis, 0), // added to durationMillis, but only in addMillisPercentage of the cases where this action is called
      addMillisPercentage: getArg(args.addMillisPercentage, 0), // percentage of the cases where addMillis is added to durationMillis when this action is called
      logDeltaMillis : getArg(args.logDeltaMillis, 100), // approx. time between two log lines
      taskName : getArg(args.taskName, "task_log"), // name of task for the test (task_XXX)
      taskParams : getArg(args.taskParams, undefined) // additional params for the task (if supported, ignored otherwise)
   }
   console.log("taskName: " + testArgs.taskName);
   console.log("delayMillis: " + testArgs.delayMillis);
   console.log("durationMillis: " + testArgs.durationMillis);
   console.log("addMillis: " + testArgs.addMillis);
   console.log("addMillisPercentage: " + testArgs.addMillisPercentage);
   console.log("logDeltaMillis: " + testArgs.logDeltaMillis);
   console.log("taskParams: " + testArgs.taskParams);
   // check if addMillis should be added to durationMillis this time (random seed is based on system time)
   if (getRandomInt(1, 100) <= testArgs.addMillisPercentage) {
      testArgs.durationMillis += testArgs.addMillis;
      console.log(">>> durationMillis updated to " + testArgs.durationMillis);
   }
   var taskFunc = tasks[testArgs.taskName];
   var info = taskFunc(testArgs);
   var totalMillis = new Date() - startMillis;
   return { greetings : `[js] hello, I am back after ${totalMillis} ms`, iterations: info.iterations, logLines: info.logLines };
}
// following code is only active when being called with nodejs from command-line
//console.log("process.env: " + JSON.stringify(process.env));
if (! process.env.__OW_API_HOST) {
  var params = '{}'
  if (process.argv.length >= 3) {
    params = process.argv[2];
  }
  info = main(JSON.parse(params));
  console.log(JSON.stringify(info));
}

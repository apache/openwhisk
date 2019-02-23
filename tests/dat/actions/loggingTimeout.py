# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

# This action prints log lines for the duration of durationMillis.
# The output is throttled by a delay of delayMillis between two log lines
# in order to keep the log size small and to stay within the log size limit.

import time

def nowInMillis():
   return int(round(time.time() * 1000))

def doLog(startMillis, testArgs):
   logLines = 0
   waitSecs = testArgs["delayMillis"] / 1000.0
   while True:
      logLines += 1
      print('[%s] The quick brown fox jumps over the lazy dog.' % logLines)
      if nowInMillis() - startMillis >= testArgs["durationMillis"]:
         break
      time.sleep(waitSecs)
   return logLines

def main(args):
   testArgs = {
      "delayMillis": args.get("delayMillis", 100),
      "durationMillis": args.get("durationMillis", 10000),
   }
   startMillis = nowInMillis()
   logLines = doLog(startMillis, testArgs)
   endMillis = nowInMillis()
   message = "hello, I'm back after %d ms and printed %d log lines" % (endMillis - startMillis, logLines)
   print(message)
   return { "message" : message }

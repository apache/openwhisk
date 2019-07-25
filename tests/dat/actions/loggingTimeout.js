/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// This action prints log lines for a defined duration.
// The output is throttled by a defined delay between two log lines
// in order to keep the log size small and to stay within the log size limit.

function getArg(value, defaultValue) {
   return value ? value : defaultValue;
}

// input: { duration: <duration in millis>, delay: <delay in millis> }, e.g.
// main( { delay: 100, duration: 10000 } );
function main(args) {

   durationMillis = getArg(args.duration, 120000);
   delayMillis = getArg(args.delay, 100);

   logLines = 0;
   startMillis = new Date();

   timeout = setInterval(function() {
      console.log(`[${ ++logLines }] The quick brown fox jumps over the lazy dog.`);
   }, delayMillis);

   return new Promise(function(resolve, reject) {
      setTimeout(function() {
         clearInterval(timeout);
         message = `hello, I'm back after ${new Date() - startMillis} ms and printed ${logLines} log lines`
         console.log(message)
         resolve({ message: message });
      }, durationMillis);
   });

}


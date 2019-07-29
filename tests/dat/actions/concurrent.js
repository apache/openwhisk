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

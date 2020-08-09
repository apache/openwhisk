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

let count=0;
function main(args) {
    console.log("sleeping for "+(args.time||1000));
    var sleepTime = args.time||1000;
    var shouldFail = count % 2 === 0;
    count = count+1;
    if (shouldFail) {
        console.log("a catastrophic failure..");
        process.exit(123);
    } else {
        return new Promise(function (resolve, reject) {
            setTimeout(function () {
                resolve({body: "done sleeping "+sleepTime, done: true});
            }, sleepTime);
        })
    }
}

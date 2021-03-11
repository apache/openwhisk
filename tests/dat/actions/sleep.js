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

/**
 * Node.js based OpenWhisk action that sleeps for the specified number
 * of milliseconds before returning. Uses a timer instead of a busy loop.
 * The function actually sleeps slightly longer than requested.
 *
 * @param parm Object with Number property sleepTimeInMs
 * @returns Object with String property msg describing how long the function slept
 *          or an error object on failure
 */
function main(parm) {
    if(!('sleepTimeInMs' in parm)) {
        const result = { error: "Parameter 'sleepTimeInMs' not specified." }
        console.error(result.error)
        return result
    }

    if(!Number.isInteger(parm.sleepTimeInMs)) {
        const result = { error: "Parameter 'sleepTimeInMs' must be an integer value." }
        console.error(result.error)
        return result
    }

    if((parm.sleepTimeInMs < 0) || !Number.isFinite(parm.sleepTimeInMs)) {
        const result = { error: "Parameter 'sleepTimeInMs' must be finite, positive integer value." }
        console.error(result.error)
        return result
    }

    console.log("Specified sleep time is " + parm.sleepTimeInMs + " ms.")

    return new Promise(function(resolve, reject) {
        const timeBeforeSleep = new Date()
        setTimeout(function () {
            const actualSleepTimeInMs = new Date() - timeBeforeSleep
            const result = { msg: "Terminated successfully after around " + actualSleepTimeInMs + " ms." }
            console.log(result.msg)
            resolve(result)
        }, parm.sleepTimeInMs)
    })
}

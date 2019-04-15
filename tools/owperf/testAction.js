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
 * Default test action for owperf. Sleeps specified time.
 * All test actions should return the invocation parameters (to stress the return path), but augmented with the execution duration and the activation id.
 * Use this code as reference if you want to create a custom test action.
 */

function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

async function main(params) {
    var start = new Date().getTime();
    params.activationId = process.env.__OW_ACTIVATION_ID;
    await sleep(parseInt(params.sleep));
    var end = new Date().getTime();
    params.duration = end - start;
    return params;
}

// Invoke main when runnig - only when setting TEST in the env
if (process.env.TEST)
    main({sleep:50}).then(params => console.log(params.duration));


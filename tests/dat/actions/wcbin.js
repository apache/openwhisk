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
 * Return word count as a binary number. This demonstrates the use of a blocking
 * invoke.
 */
var openwhisk = require('openwhisk')

function main(params) {
    var wsk = openwhisk({ignore_certs: true})

    var str = params.payload;
    console.log("The payload is '" + str + "'");

    return wsk.actions.invoke({
        actionName: 'wc',
        params: {
            payload: str
        },
        blocking: true
    }).then(activation => {
        var wordsInDecimal = activation.response.result.count;
        var wordsInBinary = wordsInDecimal.toString(2) + ' (base 2)';
        return {
            binaryCount: wordsInBinary
        };
    });
}

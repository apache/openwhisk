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

var openwhisk = require('openwhisk')

function main(args) {
    var wsk = openwhisk({ignore_certs: args.ignore_certs})

    return new Promise(function (resolve, reject) {
        return wsk.actions.list().then(list => {
            console.log("action list has this many actions:", list.length)
            if (args.name) {
                console.log('deleting')
                return wsk.actions.delete({actionName: args.name}).then(result => {
                    resolve({delete: true})
                }).catch(function (reason) {
                    console.log('error', reason)
                    reject(reason)
                })
            } else {
                console.log('ok')
                resolve({list: true})
            }
        }).catch(function (reason) {
            console.log('error', reason)
            reject(reason);
        })
    })
}

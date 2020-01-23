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

var openwhisk = require('openwhisk');

// Deletes a deployed action (named according to the playgroundId and action name) if the action exists.
function main(outerParam) {
    let param = JSON.parse(outerParam['__ow_body'])
    let playgroundId = param['playgroundId']
    let actionName = param['actionName']
    let wsk = openwhisk({ignore_certs: outerParam.__ignore_certs}) // ignores self-signed certs, necessary in some deployments
    let fullName = 'user' + playgroundId + '/' + actionName
    console.log("deleting action", fullName)
    return wsk.actions.delete(fullName).then(result => {
      console.log('deleted user action')
        return result
    }).catch(err => {
      console.error('action did not exist or error occurred', err)
    })
}

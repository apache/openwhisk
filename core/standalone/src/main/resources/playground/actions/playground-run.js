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

// Deploys code as an action and optionally runs it.
// The input parameters are
//    code -- the code to run
//    saveOnly -- if present and true, the action is not run but only deployed
//    web-export -- if present and true, the action is deployed as a web action (annotated with web-export=true).  Implies saveOnly.
//    params -- parameters to pass to the code when running it (ignored if saveOnly is present or implied)
//    playgroundId -- the identity of the browser instance submitting the code (functions as a kind of user id but not enduring
//          or authenticated).  Becomes part of the name of the action.
//    action -- the name of the action as assigned by the user or one of the default sample names; combines with playgroundId to form
//          the action name as viewed by OpenWhisk
//    runtime -- the whisk runtime ('kind') value to use in running or saving the action.
function main(outerParam) {
    let t0 = new Date().getTime()
    //console.log('outerParam: ', outerParam)
    // Get parameters
    let param = JSON.parse(outerParam['__ow_body'])
    let saveOnly = param['saveOnly']
    let webExport = param['web-export']
    let code = param['code']
    let codeParams = param['params']
    let playgroundId = param['playgroundId']
    let action = param['actionName']
    let runtime = param['runtime']
    // Deploy the action.  The action is left deployed after running it, which allows playground-fetch to fetch the code back
    // for a later edit session.  In a saveOnly or web-export scenario, the code is not even run after that.
    let wsk = openwhisk({ignore_certs: outerParam.__ignore_certs}) // ignores self-signed certs, necessary in some deployments
    let actionName = 'user' + playgroundId + '/' + action
    let annotations = {"web-export": webExport ? true : false }
    var deployParams = {name: actionName, action: code, kind: runtime, annotations: annotations}
    return wsk.actions.update(deployParams).then(uresult => {
        // Unless saveOnly, run the action once deployed.
        let t1 = new Date().getTime()
        console.log('made user action')
        if (saveOnly || webExport) {
            return { saved: true }
        } else {
            return wsk.actions.invoke({ actionName: actionName, blocking: true, params: codeParams }).then(aresult => {
                // Return the result
                let t2 = new Date().getTime()
                console.log('aresult: ', aresult)
                let response = aresult['response']
                let result = response['result']
                return { param: param, result: result, deployTime: t1 - t0, runTime: t2 - t1 }
            }).catch(err => {
                console.error('error invoking action', err)
                return {error: err}
            })
        }
    }).catch(err => {
        console.error('error creating action', err)
        return {error: err}
    })
}

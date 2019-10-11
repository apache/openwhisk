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

// Returns the package structure for a given user, creating it if it doesn't exist.
// Used to initialize playground state when an existing user loads the playground page and also to begin the
// process with an empty package for a new user.
// This code also maintains a lastSession date as a package annotation.  This denotes the last time
// this user opened the playground and can be used to expire the package.
function main(outerParam) {
    let param = JSON.parse(outerParam['__ow_body'])
    let playgroundId = param['playgroundId']
    let wsk = openwhisk({ignore_certs: outerParam.__ignore_certs}) // ignores self-signed certs, necessary in some deployments
    let name = "user" + playgroundId
    let ts = new Date().toISOString()
    let tsAnnotation = { key: "lastSession", value: ts }
    return wsk.packages.get(name).then(result => {
       console.log('found existing package', result)
       let annotations = result.annotations
       annotations.push(tsAnnotation)
       return wsk.packages.update({"name": name, "package": {annotations: annotations}}).then(_ => {
         // Return original response, which has the old timestamp.  Client does not use the timestamp in the response.
         // The response from the update does not include the package list.
         return result
       }).catch(err => {
         console.log("could not add lastSession annotation (proceeding)", err)
         return result // even if not updated
       })
    }).catch(err => {
      console.log('package does not exist or other error')
      if (err.statusCode === 404) {
        // Simple not found error.  Just create the package
        return wsk.packages.create({"name": name, "package": { annotations: [ tsAnnotation ]}}).then(result => {
          console.log('created package', result)
          return result
        }).catch(err => {
          console.error('error creating package', err)
          return { error: err }
        })
      } else {
        console.error('unrecoverable error retrieving package', err)
        return { error: err }
      }
    })
}

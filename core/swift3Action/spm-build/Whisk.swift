/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import Foundation
import Dispatch
import KituraNet

class Whisk {
    class func invoke(actionNamed action : String, withParameters params : [String:Any]) -> [String:Any] {
        let parsedAction = parseQualifiedName(name: action)
        let path = "/api/v1/namespaces/\(parsedAction.namespace)/actions/\(parsedAction.name)?blocking=true"
        
        return postSyncronish(uriPath: path, params: params)
    }
    
    class func trigger(eventNamed event : String, withParameters params : [String:Any]) -> [String:Any] {
        let parsedEvent = parseQualifiedName(name: event)
        let path = "/api/v1/namespaces/\(parsedEvent.namespace)/triggers/\(parsedEvent.name)?blocking=true"
        
        return postSyncronish(uriPath: path, params: params)
    }
    
    // handle the GCD dance to make the post async, but then obtain/return
    // the result from this function sync
    private class func postSyncronish(uriPath path: String, params : [String:Any]) -> [String:Any] {
        var response : [String:Any]!
        
        let invokeGroup = DispatchGroup()
        invokeGroup.enter()
        
        let queue = DispatchQueue(label: "com.ibm.openwhisk.post.queue" , qos: DispatchQoS.userInitiated, attributes:.concurrent, autoreleaseFrequency: .inherit, target: nil)
        
        queue.async {
            post(uriPath: path, params: params) { result in
                response = result
                invokeGroup.leave()
            }
        }
        
        // On one hand, FOREVER seems like an awfully long time...
        // But on the other hand, I think we can rely on the system to kill this
        // if it exceeds a reasonable execution time.
        switch invokeGroup.wait(timeout: DispatchTime.distantFuture) {
        case DispatchTimeoutResult.success:
            break
        case DispatchTimeoutResult.timedOut:
            break
        }
        
        return response
    }
    
    /*
     * Initialize with host, port and authKey determined from environment variables
     * EDGE_HOST and AUTH_KEY, respectively
     */
    private class func initializeCommunication() -> (host : String, port : Int16, authKey : String) {
        let env = ProcessInfo.processInfo.environment
        
        var edgeHost : String!
        if let edgeHostEnv : String = env["EDGE_HOST"] {
            edgeHost = "\(edgeHostEnv)"
        } else {
            fatalError("EDGE_HOST environment variable was not set.")
        }
        
        let hostComponents = edgeHost.components(separatedBy: ":")
        let host = hostComponents[0]
        
        var port : Int16 = 80
        if hostComponents.count == 2 {
            port = Int16(hostComponents[1])!
        }
        
        var authKey = "authKey"
        if let authKeyEnv : String = env["AUTH_KEY"] {
            authKey = authKeyEnv
        }
        
        return (host, port ,authKey)
    }
    
    // actually do the POST call to the specified OpenWhisk URI path
    private class func post(uriPath: String, params : [String:Any], callback : @escaping([String:Any]) -> Void) {
        let communicationDetails = initializeCommunication()
        
        let loginData: Data = communicationDetails.authKey.data(using: String.Encoding.utf8, allowLossyConversion: false)!
        let base64EncodedAuthKey  = loginData.base64EncodedString(options: NSData.Base64EncodingOptions(rawValue: 0))
        
        let headers = ["Content-Type" : "application/json",
                       "Authorization" : "Basic \(base64EncodedAuthKey)"]
        
        // TODO vary the schema based on the port?
        let requestOptions = [ClientRequest.Options.schema("https://"),
                              ClientRequest.Options.method("post"),
                              ClientRequest.Options.hostname(communicationDetails.host),
                              ClientRequest.Options.port(communicationDetails.port),
                              ClientRequest.Options.path(uriPath),
                              ClientRequest.Options.headers(headers),
                              ClientRequest.Options.disableSSLVerification]
        
        let request = HTTP.request(requestOptions) { response in
            if response != nil {
                do {
                    // this is odd, but that's just how KituraNet has you get
                    // the response as NSData
                    var jsonData = Data()
                    try response!.readAllData(into: &jsonData)
                    
                    let resp = try JSONSerialization.jsonObject(with: jsonData, options: [])
                    callback(resp as! [String:Any])
                } catch {
                    callback(["error": "Could not parse a valid JSON response."])
                }
            } else {
                callback(["error": "Did not receive a response."])
            }
        }
        
        do {
            //#if os(OSX)
                let jsonData = try JSONSerialization.data(withJSONObject: params as! [String:AnyObject], options: [])
            //#elseif os(Linux)
            //    let jsonData = try JSONSerialization.data(withJSONObject: params, options: [])
            //#endif
            
            print(jsonData)
            
            request.write(from: jsonData)
            request.end()
        } catch {
            callback(["error": "Could not parse parameters."])
        }
    }
    
    // separate an OpenWhisk qualified name (e.g. "/whisk.system/samples/date")
    // into namespace and name components
    private class func parseQualifiedName(name qualifiedName : String) -> (namespace : String, name : String) {
        let defaultNamespace = "_"
        let delimiter = "/"
        
        let segments :[String] = qualifiedName.components(separatedBy: delimiter)
        
        if segments.count > 2 {
            return (segments[1], Array(segments[2..<segments.count]).joined(separator: delimiter))
        } else {
            // allow both "/theName" and "theName"
            let name = qualifiedName.hasPrefix(delimiter) ? segments[1] : segments[0]
            return (defaultNamespace, name)
        }
    }
}

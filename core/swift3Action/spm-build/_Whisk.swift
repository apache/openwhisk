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
import KituraNet
import Dispatch

class Whisk {
    class func invoke(actionNamed action : String, withParameters params : [String:Any], blocking: Bool = true) -> [String:Any] {
        let parsedAction = parseQualifiedName(name: action)
        let strBlocking = blocking ? "true" : "false"
        let path = "/api/v1/namespaces/\(parsedAction.namespace)/actions/\(parsedAction.name)?blocking=\(strBlocking)"

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

        let queue = DispatchQueue.global()
        let invokeGroup = DispatchGroup()

        invokeGroup.enter()
        queue.async {
            post(uriPath: path, params: params, group: invokeGroup) { result in
                response = result
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

    /**
     * Initializes with host, port and authKey determined from environment variables
     * __OW_API_HOST and __OW_API_KEY, respectively.
     */
    private class func initializeCommunication() -> (httpType: String, host : String, port : Int16, authKey : String) {
        let env = ProcessInfo.processInfo.environment

        var edgeHost : String!
        if let edgeHostEnv : String = env["__OW_API_HOST"] {
            edgeHost = "\(edgeHostEnv)"
        } else {
            fatalError("__OW_API_HOST environment variable was not set.")
        }

        var protocolIndex = edgeHost.startIndex

        //default to https
        var httpType = "https://"
        var port : Int16 = 443

        // check if protocol is included in environment variable
        if edgeHost.hasPrefix("https://") {
            protocolIndex = edgeHost.index(edgeHost.startIndex, offsetBy: 8)
        } else if edgeHost.hasPrefix("http://") {
            protocolIndex = edgeHost.index(edgeHost.startIndex, offsetBy: 7)
            httpType = "http://"
            port = 80
        }

        let hostname = edgeHost.substring(from: protocolIndex)
        let hostComponents = hostname.components(separatedBy: ":")

        let host = hostComponents[0]

        if hostComponents.count == 2 {
            port = Int16(hostComponents[1])!
        }

        var authKey = "authKey"
        if let authKeyEnv : String = env["__OW_API_KEY"] {
            authKey = authKeyEnv
        }

        return (httpType, host, port, authKey)
    }

    // actually do the POST call to the specified OpenWhisk URI path
    private class func post(uriPath: String, params : [String:Any], group: DispatchGroup, callback : @escaping([String:Any]) -> Void) {
        let communicationDetails = initializeCommunication()

        let loginData: Data = communicationDetails.authKey.data(using: String.Encoding.utf8, allowLossyConversion: false)!
        let base64EncodedAuthKey  = loginData.base64EncodedString(options: NSData.Base64EncodingOptions(rawValue: 0))

        let headers = ["Content-Type" : "application/json",
                       "Authorization" : "Basic \(base64EncodedAuthKey)"]

        guard let encodedPath = uriPath.addingPercentEncoding(withAllowedCharacters: CharacterSet.urlQueryAllowed) else {
            callback(["error": "Error encoding uri path to make openwhisk REST call."])
            return
        }

        // TODO vary the schema based on the port?
        let requestOptions = [ClientRequest.Options.schema(communicationDetails.httpType),
                              ClientRequest.Options.method("post"),
                              ClientRequest.Options.hostname(communicationDetails.host),
                              ClientRequest.Options.port(communicationDetails.port),
                              ClientRequest.Options.path(encodedPath),
                              ClientRequest.Options.headers(headers),
                              ClientRequest.Options.disableSSLVerification]

        let request = HTTP.request(requestOptions) { response in

            // exit group after we are done
            defer {
                group.leave()
            }

            if response != nil {
                do {
                    // this is odd, but that's just how KituraNet has you get
                    // the response as NSData
                    var jsonData = Data()
                    try response!.readAllData(into: &jsonData)

                    switch WhiskJsonUtils.getJsonType(jsonData: jsonData) {
                    case .Dictionary:
                        if let resp = WhiskJsonUtils.jsonDataToDictionary(jsonData: jsonData) {
                            callback(resp)
                        } else {
                            callback(["error": "Could not parse a valid JSON response."])
                        }
                    case .Array:
                        if WhiskJsonUtils.jsonDataToArray(jsonData: jsonData) != nil {
                            callback(["error": "Response is an array, expecting dictionary."])
                        } else {
                            callback(["error": "Could not parse a valid JSON response."])
                        }
                    case .Undefined:
                        callback(["error": "Could not parse a valid JSON response."])
                    }

                } catch {
                    callback(["error": "Could not parse a valid JSON response."])
                }
            } else {
                callback(["error": "Did not receive a response."])
            }
        }

        // turn params into JSON data
        if let jsonData = WhiskJsonUtils.dictionaryToJsonString(jsonDict: params) {
            request.write(from: jsonData)
            request.end()
        } else {
            callback(["error": "Could not parse parameters."])
            group.leave()
        }

    }

    /**
     * This function is currently unused but ready when we want to switch to using URLSession instead of KituraNet.
     */
    private class func postUrlSession(uriPath: String, params : [String:Any], group: DispatchGroup, callback : @escaping([String:Any]) -> Void) {
        let communicationDetails = initializeCommunication()

        guard let encodedPath = uriPath.addingPercentEncoding(withAllowedCharacters: CharacterSet.urlQueryAllowed) else {
            callback(["error": "Error encoding uri path to make openwhisk REST call."])
            return
        }

        let urlStr = "\(communicationDetails.httpType)\(communicationDetails.host):\(communicationDetails.port)\(encodedPath)"

        if let url = URL(string: urlStr) {
            var request = URLRequest(url: url)
            request.httpMethod = "POST"

            do {
                request.addValue("application/json", forHTTPHeaderField: "Content-Type")
                request.httpBody = try JSONSerialization.data(withJSONObject: params)

                let loginData: Data = communicationDetails.authKey.data(using: String.Encoding.utf8, allowLossyConversion: false)!
                let base64EncodedAuthKey  = loginData.base64EncodedString(options: NSData.Base64EncodingOptions(rawValue: 0))
                request.addValue("Basic \(base64EncodedAuthKey)", forHTTPHeaderField: "Authorization")

                let session = URLSession(configuration: URLSessionConfiguration.default)

                let task = session.dataTask(with: request, completionHandler: {data, response, error -> Void in

                    // exit group after we are done
                    defer {
                        group.leave()
                    }

                    if let error = error {
                        callback(["error":error.localizedDescription])
                    } else {

                        if let data = data {
                            do {
                                let respJson = try JSONSerialization.jsonObject(with: data)
                                if respJson is [String:Any] {
                                    callback(respJson as! [String:Any])
                                } else {
                                    callback(["error":" response from server is not a dictionary"])
                                }
                            } catch {
                                callback(["error":"Error creating json from response: \(error)"])
                            }
                        }
                    }
                })

                task.resume()
            } catch {
                callback(["error":"Got error creating params body: \(error)"])
            }
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

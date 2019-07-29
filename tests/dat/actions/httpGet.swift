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
 * Sample code using the experimental Swift 3 runtime
 * with links to KituraNet and GCD
 */

import KituraNet
import Dispatch
import Foundation
import SwiftyJSON

func main(args:[String:Any]) -> [String:Any] {

    // Force KituraNet call to run synchronously on a global queue
    var str = "No response"
    dispatch_sync(dispatch_get_global_queue(0, 0)) {

            HTTP.get("https://httpbin.org/get") { response in

                do {
                   str = try response!.readString()!
                } catch {
                    print("Error \(error)")
                }

            }
    }

    // Assume string is JSON
    print("Got string \(str)")
    var result:[String:Any]?

    // Convert to NSData
    let data = str.data(using: NSUTF8StringEncoding, allowLossyConversion: true)!

    // test SwiftyJSON
    let json = JSON(data: data)
    if let jsonUrl = json["url"].string {
        print("Got json url \(jsonUrl)")
    } else {
        print("JSON DID NOT PARSE")
    }

    // create result object to return
    do {
        result = try NSJSONSerialization.jsonObject(with: data, options: []) as? [String: Any]
    } catch {
        print("Error \(error)")
    }

    // return, which should be a dictionary
    print("Result is \(result!)")
    return result!
}

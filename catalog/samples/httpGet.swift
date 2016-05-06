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

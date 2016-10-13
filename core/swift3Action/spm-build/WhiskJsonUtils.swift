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
import SwiftyJSON

enum WhiskJsonType {
    case Dictionary
    case Array
    case Undefined
}

class WhiskJsonUtils {
    
    class func getJsonType(jsonData: Data) -> WhiskJsonType {
        do {
            let json = try JSONSerialization.jsonObject(with: jsonData, options: [])
            if json is [String:Any] {
                return .Dictionary
            } else if json is [Any] {
                return .Array
            } else {
                return .Undefined
            }
            
        } catch {
            print("Error converting json data")
            return .Undefined
        }
        
    }
    
    class func jsonDataToArray(jsonData: Data) -> [Any]? {
        do {
            let arr = try JSONSerialization.jsonObject(with: jsonData, options: [])
            return (arr as! [Any])
        } catch {
            print("Error converting json data to dictionary \(error)")
            return nil
        }
    }
    
    class func jsonDataToDictionary(jsonData: Data) -> [String:Any]? {
        do {
            let dic = try JSONSerialization.jsonObject(with: jsonData, options: [])
            return dic as! [String:Any]
        } catch {
            print("Error converting json data to dictionary \(error)")
            return nil
        }
    }
    
    // use SwiftyJSON to serialize JSON object because of bug in Linux Swift 3.0
    // https://github.com/IBM-Swift/SwiftRuntime/issues/230
    class func dictionaryToJsonString(jsonDict: [String:Any]) -> String? {
        let json: JSON = JSON(jsonDict)
        
        if let jsonStr = json.rawString() {
            var trimmed = jsonStr.replacingOccurrences(of: "\n", with: "")
            return trimmed
        } else {
            print("Could not convert dictionary \(jsonDict) to JSON")
            return nil
        }
    }
    
    class func dictionaryToData(jsonDict: [String:Any]) -> Data? {
        let json: JSON = JSON(jsonDict)
        
        do {
            let data: Data = try json.rawData()
            return data
        } catch {
            print("Cannot convert Dictionary to Data")
            return nil
        }
    }
    
    class func arrayToJsonString(jsonArray: [Any]) -> String? {
        let json: JSON = JSON(jsonArray)
        
        if let jsonStr = json.rawString() {
            return jsonStr
        } else {
            return nil
        }
    }
    
    class func arrayToData(jsonArray: [Any]) -> Data? {
        let json: JSON = JSON(jsonArray)
        
        do {
            let data: Data = try json.rawData()
            return data
        } catch {
            print("Cannot convert Array to Data")
            return nil
        }
    }
}

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
            print("Error converting JSON data to dictionary \(error)")
            return .Undefined
        }
    }

    class func jsonDataToArray(jsonData: Data) -> [Any]? {
        do {
            let arr = try JSONSerialization.jsonObject(with: jsonData, options: [])
            return (arr as? [Any])
        } catch {
            print("Error converting JSON data to dictionary \(error)")
            return nil
        }
    }

    class func jsonDataToDictionary(jsonData: Data) -> [String:Any]? {
        do {
            let dic = try JSONSerialization.jsonObject(with: jsonData, options: [])
            return dic as? [String:Any]
        } catch {
            print("Error converting JSON data to dictionary \(error)")
            return nil
        }
    }

    // use SwiftyJSON to serialize JSON object because of bug in Linux Swift 3.0
    // https://github.com/IBM-Swift/SwiftRuntime/issues/230
    class func dictionaryToJsonString(jsonDict: [String:Any]) -> String? {
        if JSONSerialization.isValidJSONObject(jsonDict) {
            do {
                let jsonData = try JSONSerialization.data(withJSONObject: jsonDict, options: [])
                if let jsonStr = String(data: jsonData, encoding: String.Encoding.utf8) {
                    return jsonStr
                } else {
                    print("Error serializing data to JSON, data conversion returns nil string")
                }
            } catch {
                print(("\(error)"))
            }
        } else {
            print("Error serializing JSON, data does not appear to be valid JSON")
        }
        return nil
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
            let trimmed = jsonStr.replacingOccurrences(of: "\n", with: "").replacingOccurrences(of: "\r", with: "")
            return trimmed
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

    private class func escapeDict(json: [String:Any]) -> [String:Any] {
        var escaped = [String:Any]()

        for (k,v) in json {
            if v is String {
                let str = (v as! String).replacingOccurrences(of:"\"", with:"\\\"")
                escaped[k] = str
            } else if v is [String:Any] {
                escaped[k] = escapeDict(json: v as! [String : Any])
            } else if v is [Any] {
                escaped[k] = escapeArray(json: v as! [Any])
            } else {
                escaped[k] = v
            }
        }
        return escaped
    }

    private class func escapeArray(json: [Any]) -> [Any] {
        var escaped = [Any]()

        for v in json {
            if v is String {
                let str = (v as! String).replacingOccurrences(of:"\"", with:"\\\"")
                escaped.append(str)
            } else if v is [String:Any] {
                let dic = escapeDict(json: v as! [String:Any])
                escaped.append(dic)
            } else if v is [Any] {
                let arr = escapeArray(json: v as! [Any])
                escaped.append(arr)
            } else {
                escaped.append(v)
            }
        }

        return escaped
    }

    private class func escape(json: Any) -> Any? {
        if json is [String:Any] {
            let escapeObj = json as! [String:Any]
            return escapeDict(json: escapeObj)
        } else if json is [Any] {
            let escapeObj = json as! [Any]
            return escapeArray(json: escapeObj)
        } else {
            return nil
        }
    }
}

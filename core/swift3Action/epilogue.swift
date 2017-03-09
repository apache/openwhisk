
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

// Deliberate whitespaces above.

/* This code is appended to user-supplied action code.
 It reads from the standard input, deserializes into JSON and invokes the
 main function. Currently, actions print strings to stdout. This can evolve once
 JSON serialization is available in Foundation. */

import Foundation

#if os(Linux)
    import Glibc
#endif

func _whisk_json2dict(txt: String) -> [String:Any]? {
    if let data = txt.data(using: String.Encoding.utf8, allowLossyConversion: true) {
        do {
            return WhiskJsonUtils.jsonDataToDictionary(jsonData: data)
        } catch {
            return nil
        }
    }
    return nil
}


func _run_main(mainFunction: ([String: Any]) -> [String: Any]) -> Void {
    let env = ProcessInfo.processInfo.environment
    let inputStr: String = env["WHISK_INPUT"] ?? "{}"
    
    if let parsed = _whisk_json2dict(txt: inputStr) {
        let result = mainFunction(parsed)
        
        if result is [String:Any] {
            do {
                if let respString = WhiskJsonUtils.dictionaryToJsonString(jsonDict: result) {
                    print("\(respString)")
                } else {
                    print("Error converting \(result) to JSON string")
                    #if os(Linux)
                        fputs("Error converting \(result) to JSON string", stderr)
                    #endif
                }
            } catch {
                print("Error serializing response \(error)")
                #if os(Linux)
                    fputs("Error serializing response \(error)", stderr)
                #endif
            }
        } else {
            print("Cannot serialize response: \(result)")
            #if os(Linux)
                fputs("Cannot serialize response: \(result)", stderr)
            #endif
        }
    } else {
        print("Error: couldn't parse JSON input.")
        #if os(Linux)
            fputs("Error: couldn't parse JSON input.", stderr)
        #endif
    }
}

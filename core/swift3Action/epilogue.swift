
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


func _whisk_json2dict(txt: String) -> [String:Any]? {
    if let data = txt.data(using: NSUTF8StringEncoding, allowLossyConversion: true) {
        do {
            return try NSJSONSerialization.jsonObject(with: data, options: NSJSONReadingOptions.AllowFragments) as? [String:Any]
        } catch {
            return nil
        }
    }
    return nil
}


func _run_main() -> Void {
    let env = NSProcessInfo.processInfo().environment
    let inputStr: String = env["WHISK_INPUT"] ?? "{}"

    if let parsed = _whisk_json2dict(txt: inputStr) {
        let result = main(args:parsed)
        do {
            let resp = try NSJSONSerialization.data(withJSONObject: result.bridge(), options: [])
            if let string = NSString(data: resp, encoding: NSUTF8StringEncoding) {
                // send response to stdout
                print("\(string)")
            }
        } catch {
            print("Serialization failed (\(result)).")
        }
    } else {
        print("Error: couldn't parse JSON input.")
    }
}

_run_main()




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
    if let data = txt.dataUsingEncoding(NSUTF8StringEncoding) {
        do {
            return try NSJSONSerialization.JSONObjectWithData(data, options: .AllowFragments) as? [String:Any]
        } catch {
            return nil
        }
    }
    return nil
}

class _Whisk_JSONSerialization {
    enum JSONSerializationError: ErrorType {
        case UnsupportedValue(value: Any)
    }

    private class func escStr(str: String) -> String {
        return (str
            .bridge()
            .stringByReplacingOccurrencesOfString("\\", withString: "\\\\")
            .bridge()
            .stringByReplacingOccurrencesOfString("\"", withString: "\\\""))
    }

    class func serialize(value: Any) throws -> String {
        if let _ = value as? NSNull {
            return "null"
        }

        if let str = value as? String {
            return "\"\(escStr(str))\""
        }

        if let num = value as? Double {
            return "\(num)"
        }

        if let num = value as? Int {
            return "\(num)"
        }

        if let b = value as? Bool {
            return b ? "true" : "false"
        }

        // More numeric types should go above.

        let mirror = Mirror(reflecting: value)

        if mirror.displayStyle == .Collection {
            return try serializeArray(mirror.children.map({ return $0.value }))
        }

        if mirror.displayStyle == .Dictionary {
            return try serializeObject(mirror.children.map({ return $0.value }))
        }

        print("Couldn't handle \(value) of type \(value.dynamicType)")
        throw JSONSerializationError.UnsupportedValue(value: value)
    }

    private class func serializeArray(array: [Any]) throws -> String {
        if array.count == 0 {
            return "[]"
        }
        var out = "["
        var sep = " "
        for e in array {
            let es = try serialize(e)
            out = out + sep + es
            sep = ", "
        }
        out = out + " ]"
        return out
    }

    private class func serializeObject(pairs: [Any]) throws -> String {
        if pairs.count == 0 {
            return "{}"
        }
        var out = "{"
        var sep = " "
        for pair in pairs {
            let pairMirror = Mirror(reflecting: pair)
            if pairMirror.displayStyle == .Tuple && pairMirror.children.count == 2 {
                let g = pairMirror.children.generate()
                let k = g.next()!.value
                let v = g.next()!.value
                let ks = escStr(k as! String)
                let vs = try serialize(v)
                out = out + sep + "\"\(ks)\": " + vs
                sep = ", "
            } else {
                throw JSONSerializationError.UnsupportedValue(value: pairs)
            }
        }
        out = out + " }"
        return out
    }
}

func _run_main(mainFunction: [String: Any] -> [String: Any]) -> Void {
    let env = NSProcessInfo.processInfo().environment
    let inputStr: String = env["WHISK_INPUT"] ?? "{}"

    if let parsed = _whisk_json2dict(inputStr) {
        let result = mainFunction(parsed)
        do {
            try print(_Whisk_JSONSerialization.serialize(result))
        } catch {
            print("Serialization failed (\(result)).")
        }
    } else {
        print("Error: couldn't parse JSON input.")
    }
}


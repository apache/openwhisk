// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements; and to You under the Apache License, Version 2.0.imitations under the License.

func main(args: [String:Any]) -> [String:Any] {
    if let text = args["text"] as? String {
        print("Hello " + text + "!")
        return [ "payload" : "Hello " + text + "!" ]
    } else {
        print("Hello stranger!")
        return [ "payload" : "Hello stranger!" ]
    }
}

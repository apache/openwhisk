// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements; and to You under the Apache License, Version 2.0.

func main(args: [String:Any]) -> [String:Any] {
    if let str = args["delimiter"] as? String {
        let msg = "\(str) ☃ \(str)"
        print(msg)
        return [ "winter" : msg ]
    } else {
        return [ "error" : "no delimiter" ]
    }
}

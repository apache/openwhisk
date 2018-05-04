// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements; and to You under the Apache License, Version 2.0.

func main(args: [String:Any]) -> [String:Any] {
  if let triggerName = args["triggerName"] as? String {
    print("Trigger Name: \(triggerName)")
    return Whisk.trigger(eventNamed: triggerName, withParameters: [:])
  } else {
    return ["error": "You must specify a triggerName parameter!"]
  }
}

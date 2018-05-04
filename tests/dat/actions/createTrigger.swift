// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements; and to You under the Apache License, Version 2.0.

func main(args: [String:Any]) -> [String:Any] {
 guard let triggerName = args["triggerName"] as? String else {
    return ["error": "You must specify a triggerName parameter!"]
  }
  print("Trigger Name: \(triggerName)")
  return Whisk.createTrigger(triggerNamed: triggerName, withParameters: [:])
}

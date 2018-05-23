// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements; and to You under the Apache License, Version 2.0.

import SwiftyJSON

func main(args: [String:Any]) -> [String:Any] {
  let invokeResult = Whisk.invoke(actionNamed: "/whisk.system/utils/date", withParameters: [:])
  let dateActivation = JSON(invokeResult)

  // the date we are looking for is the result inside the date activation
  if let dateString = dateActivation["response"]["result"]["date"].string {
    print("It is now \(dateString)")
  } else {
    print("Could not parse date of of the response.")
  }

  // return the entire invokeResult
  return invokeResult
}

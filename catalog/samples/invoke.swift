import SwiftyJSON

func main(args: [String:Any]) -> [String:Any] {
  let invokeResult = Whisk.invoke(actionNamed: "/whisk.system/util/date", withParameters: [:])
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

import SwiftyJSON

func main(args: [String:Any]) -> [String:Any] {
  let invokeResult = Whisk.invoke(actionNamed: "/whisk.system/utils/date", withParameters: [:], blocking: false)
  let dateActivation = JSON(invokeResult)

  // the date we are looking for is the result inside the date activation
  if let activationId = dateActivation["activationId"].string {
    print("Invoked.")
  } else {
    print("Failed to invoke.")
  }

  // return the entire invokeResult
  return invokeResult
}

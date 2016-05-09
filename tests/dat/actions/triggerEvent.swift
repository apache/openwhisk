import SwiftyJSON

func main(args: [String:Any]) -> [String:Any] {
  let response = Whisk.trigger(eventNamed: "TestTrigger", withParameters: [:])

  return response
}

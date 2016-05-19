func main(args: [String:Any]) -> [String:Any] {
  if let triggerName = args["triggerName"] as? String {
    print("Tigger Name: \(triggerName)")
    return Whisk.trigger(eventNamed: triggerName, withParameters: [:])
  } else {
    return ["error": "You must specify a triggerName parameter!"]
  }
}

func main(args: [String:Any]) -> [String:Any] {
  guard let triggerName = args["triggerName"] as? String else {
      return ["error": "You must specify a triggerName parameter!"]
  }
  guard let actionName = args["actionName"] as? String else {
      return ["error": "You must specify a actionName parameter!"]
  }
  guard let ruleName = args["ruleName"] as? String else {
      return ["error": "You must specify a ruleName parameter!"]
  }
  print("Rule Name: \(ruleName), Trigger Name: \(triggerName), actionName: \(actionName)")
  return Whisk.createRule(ruleNamed: ruleName, withTrigger: triggerName, andAction: actionName)
}

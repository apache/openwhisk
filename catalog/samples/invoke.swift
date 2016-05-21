import SwiftyJSON

func main(args: [String:Any]) -> [String:Any] {
  let response = Whisk.invoke(actionNamed: "/whisk.system/util/date", withParameters: [:])

  let jsonString = response["response"] as! String

  if let dataFromString = jsonString.data(using: NSUTF8StringEncoding, allowLossyConversion: true) {
    let json = JSON(data: dataFromString)
    if let date = json["response", "result", "date"].string {
      print("It is now \(date)")
      return ["response" : json["response"].stringValue]
    } else {
      print("Could not parse date of of the response.")
      return ["error" : "Could not parse date of of the response."]
    }
  } else {
    print("Could not invoke date action.")
    return ["error" : "Could not invoke date action."]
  }
}

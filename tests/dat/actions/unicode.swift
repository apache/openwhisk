//  Copyright ©
func main(args: [String:Any]) -> [String:Any] {
    if let str = args["delimiter"] as? String {
        let msg = "\(str) ☃ \(str)"
        print(msg)
        return [ "winter" : msg ]
    } else {
        return [ "error" : "no delimiter" ]
    }
}
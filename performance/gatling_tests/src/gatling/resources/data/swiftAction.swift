func main(args: [String:Any]) -> [String:Any] {
    if let text = args["text"] as? String {
        print("Hello " + text + "!")
        return [ "payload" : "Hello " + text + "!" ]
    } else {
        print("Hello stranger!")
        return [ "payload" : "Hello stranger!" ]
    }
}

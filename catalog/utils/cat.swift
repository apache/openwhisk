/**
 * Sample code using the experimental Swift 3 runtime
 * Equivalent to unix cat command.
 * Return all the lines in an array. All other fields in the input message are stripped.
 * @param lines An array of strings.
 */
func main(args: [String:Any]) -> [String:Any] {
    if let lines = args["lines"] as? [Any] {
        var payload = ""
        for line in lines {
            payload += "\(line)\n"
        }
        return ["lines": lines, "payload": payload]
    } else {
        return ["error": "You must specify a lines parameter!"]
    }
}

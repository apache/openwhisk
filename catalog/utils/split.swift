/**
 * Sample code using the experimental Swift 3 runtime
 * Splits a string into an array of strings
 * Return lines in an array.
 * @param payload A string.
 * @param separator The separator to use for splitting the string
 */
func main(args: [String:Any]) -> [String:Any] {
    if let payload = args["payload"] as? String {
        let lines: [String]
        if let separator = args["separator"] as? String {
            lines = payload.components(separatedBy: separator)
        } else {
            lines = payload.characters.split {$0 == "\n" || $0 == "\r\n"}.map(String.init)
        }
        return ["lines": lines, "payload": payload]
    } else {
        return ["error": "You must specify a payload parameter!"]
    }
}

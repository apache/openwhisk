/**
 * Sample code using the experimental Swift 3 runtime
 * Return the first num lines of an array.
 * @param lines An array of strings.
 * @param num Number of lines to return.
 */
func main(args: [String:Any]) -> [String:Any] {
    if let lines = args["lines"] as? [Any] {
        var num: Int?
        if let value = args["num"] as? Int {
            num = value
        } else if let value = args["num"] as? Double {
            num = Int(value)
        } else {
            num = 1
        }
        return ["lines": Array(lines.prefix(num!)), "num": num!]
    } else {
        return ["error": "You must specify a lines parameter!"]
    }
}

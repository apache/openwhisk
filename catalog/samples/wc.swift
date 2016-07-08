/**
 * Sample code using the experimental Swift 3 runtime
 * A word count utility
 * @param payload A string of words to count.
 */
func main(args: [String:Any]) -> [String:Any] {
    if let str = args["payload"] as? String {
        let words = str.components(separatedBy: " ")
        let count = words.count
        print("The message '\(str)' has \(count) words")
        return ["count": count]
    } else {
        return ["error": "You must specify a payload parameter!"]
    }
}

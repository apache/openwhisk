/**
 * Sample code using the experimental Swift 3 runtime
 * Sort a set of lines.
 * @param lines An array of strings to sort.
 */
func main(args: [String:Any]) -> [String:Any] {
    if let lines = args["lines"] as? [Any] {
        let sorted = lines.sorted { (arg1, arg2) -> Bool in
            String(arg1) < String(arg2)
        }
        return ["lines": sorted, "length": lines.count]
    } else {
        return ["error": "You must specify a lines parameter!"]
    }
}

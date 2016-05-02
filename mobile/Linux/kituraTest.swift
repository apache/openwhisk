import KituraNet

func main(args:[String:Any]) -> [String:Any] {
    
    Http.get("http://www.ibm.com") { response in
        do {
            let str = try response!.readString()
            print("Got string \(str)")
        } catch {
            print("Got error \(error)")
        }
        print("Net responded")

    }
    
    return args
    
}


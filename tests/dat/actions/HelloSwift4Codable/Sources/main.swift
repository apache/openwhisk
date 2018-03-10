struct AnInput: Codable {
    let name: String?
}
struct AnOutput: Codable {
    let greeting: String?
}
 func main(input: AnInput, respondWith: (AnOutput?, Error?) -> Void) -> Void {
     if let name = input.name {
         let answer = AnOutput(greeting: "Hello \(name)!")
         respondWith(answer, nil)
     } else {
         let answer = AnOutput(greeting: "Hello stranger!")
         respondWith(answer, nil)
     }
  }
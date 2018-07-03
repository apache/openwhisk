<!--
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
-->

## Creating and invoking Swift actions

The process of creating Swift actions is similar to that of [other actions](actions.md#the-basics).
The following sections guide you through creating and invoking a single Swift action,
and demonstrate how to bundle multiple Swift files and third party dependencies.

**Tip:** You can use the [Online Swift Playground](http://online.swiftplayground.run) to test your Swift code without having to install Xcode on your machine.

**Note:** Swift actions run in a Linux environment. Swift on Linux is still in development,
and OpenWhisk usually uses the latest available release, which is not necessarily stable.
In addition, the version of Swift that is used with OpenWhisk might be inconsistent with versions
of Swift from stable releases of Xcode on MacOS.

### Swift 3
An action is simply a top-level Swift function. For example, create a file called
`hello.swift` with the following content:

```swift
func main(args: [String:Any]) -> [String:Any] {
    if let name = args["name"] as? String {
        return [ "greeting" : "Hello \(name)!" ]
    } else {
        return [ "greeting" : "Hello stranger!" ]
    }
}
```
In this example the Swift action consumes a dictionary and produces a dictionary.

You can create an OpenWhisk action called `helloSwift` from this function as
follows:

```
wsk action create helloSwift hello.swift --kind swift:3.1.1
```

### Swift 4

New in Swift 4 in addition of the above main function signature there are two more signatures out of the box taking advantage of the [Codable](https://developer.apple.com/documentation/swift/codable) type. You can learn more about data types encodable and decodable for compatibility with external representations such as JSON [here](https://developer.apple.com/documentation/foundation/archives_and_serialization/encoding_and_decoding_custom_types).

The following takes as input parameter a Codable Input with field `name`, and returns a Codable output with a field `greetings`
```swift
struct Input: Codable {
    let name: String?
}
struct Output: Codable {
    let greeting: String
}
func main(param: Input, completion: (Output?, Error?) -> Void) -> Void {
    let result = Output(greeting: "Hello \(param.name ?? "stranger")!")
    print("Log greeting:\(result.greeting)")
    completion(result, nil)
}
```
In this example the Swift action consumes a Codable and produces a Codable type.
If you don't need to handle any input you can use the function signature that doesn't take any input, only Codable output.
```swift
struct Output: Codable {
    let greeting: String
}
func main(completion: (Output?, Error?) -> Void) -> Void {
    let result = Output(greeting: "Hello OpenWhisk!")
    completion(result, nil)
}
```


You can create a OpenWhisk action called `helloSwift` from this function as
follows:

```
wsk action create helloSwift hello.swift --kind swift:4.1
```


See the Swift [reference](#reference.md) for more information about the Swift runtime.

Action invocation is the same for Swift actions as it is for JavaScript actions:

```
wsk action invoke --result helloSwift --param name World
```

```json
{
  "greeting": "Hello World!"
}
```

Find out more about parameters in the [Working with parameters](./parameters.md) section.

## Packaging an action as a Swift executable

When you create an OpenWhisk Swift action with a Swift source file, it has to be compiled into a binary before the action is run. Once done, subsequent calls to the action are much faster until the container holding your action is purged. This delay is known as the cold-start delay.

To avoid the cold-start delay, you can compile your Swift file into a binary and then upload to OpenWhisk in a zip file. As you need the OpenWhisk scaffolding, the easiest way to create the binary is to build it within the same environment as it will be run in.

## Using a script to build Swift packaged action
You can use a script to automate the packaging of the action. Create  script `compile.sh`h file the following.
```bash
#!/bin/bash
set -ex

if [ -z "$1" ] ; then
  echo 'Error: Missing action name'
  exit 1
fi
if [ -z "$2" ] ; then
  echo 'Error: Missing kind, for example swift:4.1'
  exit 2
fi
OUTPUT_DIR="build"
if [ ${2} == "swift:3.1.1" ]; then
  BASE_PATH="/swift3Action"
  DEST_SOURCE="$BASE_PATH/spm-build"
  RUNTIME="openwhisk/action-swift-v3.1.1"
elif [ ${2} == "swift:4.1" ]; then
  RUNTIME="openwhisk/action-swift-v4.1"
  BASE_PATH="/swift4Action"
  DEST_SOURCE="/$BASE_PATH/spm-build/Sources/Action"
else
  echo "Error: Kind $2 not recognize"
  exit 3
fi
DEST_PACKAGE_SWIFT="$BASE_PATH/spm-build/Package.swift"

BUILD_FLAGS=""
if [ -n "$3" ] ; then
  BUILD_FLAGS=${3}
fi

echo "Using runtime $RUNTIME to compile swift"
docker run --rm --name=compile-ow-swift -it -v "$(pwd):/owexec" $RUNTIME bash -ex -c "

if [ -f \"/owexec/$OUTPUT_DIR/$1.zip\" ] ; then
  rm \"/owexec/$OUTPUT_DIR/$1.zip\"
fi

echo 'Setting up build...'
cp /owexec/actions/$1/Sources/*.swift $DEST_SOURCE/

# action file can be either {action name}.swift or main.swift
if [ -f \"$DEST_SOURCE/$1.swift\" ] ; then
  echo 'renaming $DEST_SOURCE/$1.swift $DEST_SOURCE/main.swift'
  mv \"$DEST_SOURCE/$1.swift\" $DEST_SOURCE/main.swift
fi
# Add in the OW specific bits
cat $BASE_PATH/epilogue.swift >> $DEST_SOURCE/main.swift
echo '_run_main(mainFunction:main)' >> $DEST_SOURCE/main.swift

# Only for Swift4
if [ ${2} != "swift:3.1.1" ]; then
  echo 'Adding wait to deal with escaping'
  echo '_ = _whisk_semaphore.wait(timeout: .distantFuture)' >> $DEST_SOURCE/main.swift
fi

echo \"Compiling $1...\"
cd /$BASE_PATH/spm-build
cp /owexec/actions/$1/Package.swift $DEST_PACKAGE_SWIFT
# we have our own Package.swift, do a full compile
swift build ${BUILD_FLAGS} -c release

echo 'Creating archive $1.zip...'
#.build/release/Action
mkdir -p /owexec/$OUTPUT_DIR
zip \"/owexec/$OUTPUT_DIR/$1.zip\" .build/release/Action

"
```

The script assumes you have a directory `actions` with each top level directory representing an action.
```
actions/
├── hello
│   ├── Package.swift
│   └── Sources
│       └── main.swift
```

- Create the `Package.swift` file to add dependencies.
The syntax is different from Swift 3 to Swift 4 tools.
For Swift 3 here is an example:
  ```swift
  import PackageDescription

  let package = Package(
      name: "Action",
          dependencies: [
              .Package(url: "https://github.com/apple/example-package-deckofplayingcards.git", majorVersion: 3),
              .Package(url: "https://github.com/IBM-Swift/CCurl.git", "0.2.3"),
              .Package(url: "https://github.com/IBM-Swift/Kitura-net.git", "1.7.10"),
              .Package(url: "https://github.com/IBM-Swift/SwiftyJSON.git", "15.0.1"),
              .Package(url: "https://github.com/watson-developer-cloud/swift-sdk.git", "0.16.0")
          ]
  )
  ```
  For Swift 4 here is an example:
  ```swift
  // swift-tools-version:4.0
  import PackageDescription

  let package = Package(
      name: "Action",
      products: [
          .executable(
              name: "Action",
              targets:  ["Action"]
          )
      ],
      dependencies: [
          .package(url: "https://github.com/apple/example-package-deckofplayingcards.git", .upToNextMajor(from: "3.0.0"))
      ],
      targets: [
          .target(
              name: "Action",
              dependencies: ["DeckOfPlayingCards"],
              path: "."
          )
      ]
  )
  ```
  As you can see this example adds `example-package-deckofplayingcards` as a dependency.
  Notice that `CCurl`, `Kitura-net` and `SwiftyJSON` are provided in the standard Swift action
and so you should include them in your own `Package.swift` only for Swift 3 actions.

- Build the action by running the following command for a Swift 3 action:
  ```
  bash compile.sh hello swift:3.1.1
  ```
  To compile for Swift 4 use `swift:4.1` instead of `swift:3.1.1`
  ```
  bash compile.sh hello swift:4.1
  ```
  This has created hello.zip in the `build`.

- Upload it to OpenWhisk with the action name helloSwifty:
  For Swift 3 use the kind `swift:3.1.1`
  ```
  wsk action update helloSwiftly build/hello.zip --kind swift:3.1.1
  ```
  For Swift 4 use the kind `swift:3.1.1`
  ```
  wsk action update helloSwiftly build/hello.zip --kind swift:4.1
  ```

- To check how much faster it is, run
  ```
  wsk action invoke helloSwiftly --blocking
  ```

  The time it took for the action to run is in the "duration" property and compare to the time it takes to run with a compilation step in the hello action.

## Error Handling in Swift 4

With the new Codable completion handler, you can pass an Error to indicate a failure in your Action.
[Error handling in Swift](https://developer.apple.com/library/content/documentation/Swift/Conceptual/Swift_Programming_Language/ErrorHandling.html) resembles exception handling in other languages, with the use of the `try, catch` and `throw` keywords.
The following example shows a an example on handling an error
```swift
enum VendingMachineError: Error {
    case invalidSelection
    case insufficientFunds(coinsNeeded: Int)
    case outOfStock
}
func main(param: Input, completion: (Output?, Error?) -> Void) -> Void {
    // Return real error
    do {
        throw VendingMachineError.insufficientFunds(coinsNeeded: 5)
    } catch {
        completion(nil, error)
    }
}
```

## Reference

### Swift 3
Swift 3 actions are executed using Swift 3.1.1  `--kind swift:3.1.1`.

Swift 3.1.1 actions can use the following packages:
- KituraNet version 1.7.6, https://github.com/IBM-Swift/Kitura-net
- SwiftyJSON version 15.0.1, https://github.com/IBM-Swift/SwiftyJSON
- Watson Developer Cloud SDK version 0.16.0, https://github.com/watson-developer-cloud/swift-sdk

### Swift 4
Swift 4 actions are executed using Swift 4.1  `--kind swift:4.1`.
The default `--kind swift:default` is Swift 4.1.

Swift 4.1 action runtime doesn't embed any packages, follow the instructions for [packaged swift actions](./actions.md#packaging-an-action-as-a-swift-executable) to include dependencies using a Package.swift.

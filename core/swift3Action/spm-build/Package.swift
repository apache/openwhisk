import PackageDescription

let package = Package(
    name: "Action",
        dependencies: [
            .Package(url: "https://github.com/IBM-Swift/Kitura-net.git", "0.15.6"),
            .Package(url: "https://github.com/IBM-Swift/SwiftyJSON.git", majorVersion: 7),
            .Package(url: "https://github.com/IBM-Swift/swift-watson-sdk.git", majorVersion: 0, minor: 2),
        ]
)

import PackageDescription

let package = Package(
    name: "Action",
        dependencies: [
            .Package(url: "https://github.com/IBM-Swift/Kitura-net.git", "0.13.8"),
            .Package(url: "https://github.com/IBM-Swift/SwiftyJSON.git", majorVersion: 7)
        ]
)

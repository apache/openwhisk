import PackageDescription

let package = Package(
    name: "Action",
        dependencies: [
            .Package(url: "https://github.com/IBM-Swift/Kitura-net.git", majorVersion: 0, minor: 13),
            .Package(url: "https://github.com/IBM-Swift/SwiftyJSON.git", majorVersion: 7)
        ]
)
// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CamHubCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "CamHubCore", targets: ["CamHubCore"])],
    targets: [
        .target(name: "CamHubCore"),
        .testTarget(name: "CamHubCoreTests", dependencies: ["CamHubCore"])
    ]
)

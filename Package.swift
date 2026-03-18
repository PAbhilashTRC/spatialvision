// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CapacitorSpatialVision",
    platforms: [.iOS(.v15)],
    products: [
        .library(
            name: "CapacitorSpatialVision",
            targets: ["SpatialVisionPlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0")
    ],
    targets: [
        .target(
            name: "SpatialVisionPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm")
            ],
            path: "ios/Sources/SpatialVisionPlugin"),
        .testTarget(
            name: "SpatialVisionPluginTests",
            dependencies: ["SpatialVisionPlugin"],
            path: "ios/Tests/SpatialVisionPluginTests")
    ]
)
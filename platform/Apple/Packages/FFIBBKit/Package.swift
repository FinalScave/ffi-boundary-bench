// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "FFIBBKit",
    platforms: [
        .macOS(.v13),
        .iOS(.v15),
    ],
    products: [
        .library(name: "FFIBBKit", targets: ["FFIBBKit"]),
    ],
    targets: [
        .binaryTarget(
            name: "FFIBB_macOS",
            path: "Vendor/macOS/FFIBB.xcframework"
        ),
        .binaryTarget(
            name: "FFIBB_iOS",
            path: "Vendor/iOS/FFIBB.xcframework"
        ),
        .target(
            name: "FFIBBKit",
            dependencies: [
                .target(name: "FFIBB_macOS", condition: .when(platforms: [.macOS])),
                .target(name: "FFIBB_iOS", condition: .when(platforms: [.iOS])),
            ]
        ),
    ]
)

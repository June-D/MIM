// swift-tools-version: 5.9
import PackageDescription
import Foundation

// Rust 动态库位置：默认取仓库内的 release 产物，可用 MIM_RUST_LIB 覆盖。
// iOS 上不是 dylib 而是 xcframework 里的静态库，届时这里会换成 .binaryTarget。
let packageRoot = URL(fileURLWithPath: #filePath).deletingLastPathComponent().path
let rustLibDir = Context.environment["MIM_RUST_LIB"]
    ?? "\(packageRoot)/../../MIMCoreRust/target/release"

let package = Package(
    name: "MIMKit",
    platforms: [.macOS(.v13), .iOS(.v16)],
    products: [
        .library(name: "MIMKit", targets: ["MIMKit"]),
    ],
    targets: [
        // C ABI 的模块封装：iOS 侧通过它 import，不需要 JNI。
        .systemLibrary(name: "MIMCoreABI", path: "Sources/MIMCoreABI"),
        .target(
            name: "MIMKit",
            dependencies: ["MIMCoreABI"],
            linkerSettings: [
                // -L 指到 Rust 产物；-rpath 保证运行时也能找到 dylib。
                // unsafeFlags 只影响本地构建（本包不作为远程依赖发布）。
                .unsafeFlags(["-L", rustLibDir]),
                .unsafeFlags(["-Xlinker", "-rpath", "-Xlinker", rustLibDir]),
            ]
        ),
        // 不依赖 XCTest 的冒烟程序：Command Line Tools 里没有 XCTest，
        // 但它现在就能跑，装 Xcode 之前也能验证这层是通的。
        .executableTarget(name: "MIMKitSmoke", dependencies: ["MIMKit"]),
        .testTarget(name: "MIMKitTests", dependencies: ["MIMKit"]),
    ]
)

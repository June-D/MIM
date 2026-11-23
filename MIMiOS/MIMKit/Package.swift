// swift-tools-version: 5.9
import PackageDescription
import Foundation

// 两套链接方式，按平台切换：
//
// - macOS：链接 `MIMCoreRust/target/release/libmim_core_rust.dylib`（`swift run MIMKitSmoke`
//   不需要 Xcode，是日常验证路径）；
// - iOS：链接 `MimCore.xcframework`（iOS 不允许分发 dylib，必须静态库）。
//
// 为什么用环境变量而不是无条件的 `binaryTarget`：SwiftPM 在加载清单时就会校验
// `binaryTarget` 的路径，产物不存在会直接让 **macOS 也编不了**——那等于把
// 「没有 Xcode 也能验证库层」这个性质弄丢了。所以只有显式 `MIM_USE_IOS_FRAMEWORK=1`
// （由 `xcodebuild` 建 iOS 时设置）才要求 xcframework 存在。
let packageRoot = URL(fileURLWithPath: #filePath).deletingLastPathComponent().path
let rustLibDir = Context.environment["MIM_RUST_LIB"]
    ?? "\(packageRoot)/../../MIMCoreRust/target/release"
let useIOSFramework = Context.environment["MIM_USE_IOS_FRAMEWORK"] == "1"
// SwiftPM 只接受包内相对路径，所以 xcframework 由 build-ios.sh 产出到包根目录。
let frameworkPath = Context.environment["MIM_IOS_FRAMEWORK"] ?? "MimCore.xcframework"

var targets: [Target] = []

if useIOSFramework {
    targets.append(.binaryTarget(name: "MIMCoreABI", path: frameworkPath))
    targets.append(
        .target(
            name: "MIMKit",
            // xcframework 里的模块名就是 MIMCoreABI，所以 `import MIMCoreABI` 在两端一致。
            dependencies: ["MIMCoreABI"]
        )
    )
} else {
    targets.append(.systemLibrary(name: "MIMCoreABI", path: "Sources/MIMCoreABI"))
    targets.append(
        .target(
            name: "MIMKit",
            dependencies: ["MIMCoreABI"],
            linkerSettings: [
                // -L 指到 Rust 产物；-rpath 保证运行时也能找到 dylib。
                // unsafeFlags 只影响本地构建（本包不作为远程依赖发布）。
                .unsafeFlags(["-L", rustLibDir]),
                .unsafeFlags(["-Xlinker", "-rpath", "-Xlinker", rustLibDir]),
            ]
        )
    )
}

targets.append(.executableTarget(name: "MIMKitSmoke", dependencies: ["MIMKit"]))
targets.append(.testTarget(name: "MIMKitTests", dependencies: ["MIMKit"]))

let package = Package(
    name: "MIMKit",
    platforms: [.macOS(.v13), .iOS(.v16)],
    products: [
        .library(name: "MIMKit", targets: ["MIMKit"]),
    ],
    targets: targets
)

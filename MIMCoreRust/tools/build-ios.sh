#!/usr/bin/env bash
#
# 把协议核心编译成 iOS 可链接的静态库，并打成 xcframework。
#
# 用法：
#   MIMCoreRust/tools/build-ios.sh            # 产出 target/ios/MimCore.xcframework
#   MIMCoreRust/tools/build-ios.sh --check    # 只检查环境，不编译
#
# 前置：Xcode（不是只有 Command Line Tools）+ rustup target
#   sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
#   rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUST_DIR="$ROOT/MIMCoreRust"
OUT="$RUST_DIR/target/ios"
LIB="libmim_core_rust.a"
CHECK_ONLY=0

for arg in "$@"; do
    case "$arg" in
        --check) CHECK_ONLY=1 ;;
        -h|--help) sed -n '2,14p' "${BASH_SOURCE[0]}"; exit 0 ;;
    esac
done

# 1) 必须有 Xcode：只用 Command Line Tools 时，链接会撞到 macOS SDK 的 libiconv，
#    报 "building for 'iOS-simulator', but linking in dylib ... built for 'macOS'"。
if ! xcode-select -p 2>/dev/null | grep -q "Xcode.app"; then
    echo "需要 Xcode（当前是 $(xcode-select -p 2>/dev/null || echo 未配置)）。" >&2
    echo "装好后执行：sudo xcode-select -s /Applications/Xcode.app/Contents/Developer" >&2
    exit 1
fi
# Xcode 装了但许可没接受时，xcrun / ld 都会失败，而且报错埋在链接器输出里，
# 很难一眼看出跟「许可」有关。这里先探一次，给出可执行的修复命令。
if xcodebuild -version 2>&1 | grep -q "license agreements"; then
    echo "Xcode 许可协议未接受，先执行：sudo xcodebuild -license accept" >&2
    exit 1
fi

SDK_SIM="$(xcrun --sdk iphonesimulator --show-sdk-path 2>/dev/null || true)"
SDK_DEV="$(xcrun --sdk iphoneos --show-sdk-path 2>/dev/null || true)"
if [[ -z "$SDK_SIM" || -z "$SDK_DEV" ]]; then
    echo "找不到 iOS SDK。两种可能：" >&2
    echo "  1) Xcode 许可未接受：sudo xcodebuild -license accept" >&2
    echo "  2) 未安装 iOS 平台组件：xcodebuild -downloadPlatform iOS" >&2
    exit 1
fi

# 2) Rust target 是否齐全
for target in aarch64-apple-ios aarch64-apple-ios-sim; do
    if ! rustup target list --installed | grep -qx "$target"; then
        echo "缺少 Rust target：$target（rustup target add $target）" >&2
        exit 1
    fi
done

if [[ "$CHECK_ONLY" == "1" ]]; then
    echo "环境检查通过：Xcode=$(xcode-select -p)"
    echo "  iOS SDK=${SDK_DEV}"
    echo "  模拟器 SDK=${SDK_SIM}"
    exit 0
fi

# 3) 分别编译设备与模拟器（静态库不需要签名）
cd "$RUST_DIR"
echo "编译 aarch64-apple-ios（真机）…"
cargo build --release --target aarch64-apple-ios

echo "编译 aarch64-apple-ios-sim（模拟器）…"
cargo build --release --target aarch64-apple-ios-sim

# 4) 打 xcframework：设备库 + 模拟器库各一个 slice
rm -rf "$OUT"
mkdir -p "$OUT"
xcrun xcodebuild -create-xcframework \
    -library "target/aarch64-apple-ios/release/$LIB" \
    -headers include \
    -library "target/aarch64-apple-ios-sim/release/$LIB" \
    -headers include \
    -output "$OUT/MimCore.xcframework"

echo "完成：$OUT/MimCore.xcframework"

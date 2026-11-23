#!/usr/bin/env bash
#
# 一条命令构建 iOS App（设备架构，不签名）。
#
# 为什么默认不签名：`CODE_SIGNING_ALLOWED=NO` 只验证「能不能编、能不能链」，
# 不需要开发者账号；要装到真机时再在 Xcode 里选 Team，或传自己的参数。
#
# 用法：
#   MIMiOS/MIMiOSApp/build.sh                 # 设备架构（arm64），不签名
#   MIMiOS/MIMiOSApp/build.sh simulator       # 模拟器（需要先下载模拟器运行时）
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$APP_DIR/../.." && pwd)"
MODE="${1:-device}"
DERIVED="${MIM_DERIVED_DATA:-/tmp/mim-derived}"

if [[ ! -d "$APP_DIR/../MIMKit/MimCore.xcframework" ]]; then
    echo "缺少 MimCore.xcframework，先跑：MIMCoreRust/tools/build-ios.sh" >&2
    exit 1
fi
if [[ ! -d "$APP_DIR/MIMiOSApp.xcodeproj" ]]; then
    echo "缺少 .xcodeproj，先跑：cd $APP_DIR && xcodegen generate" >&2
    exit 1
fi

if [[ "$MODE" == "simulator" ]]; then
    # 模拟器运行时不是 Xcode 自带的，缺了会没有可用设备。
    if ! xcrun simctl list runtimes 2>/dev/null | grep -q iOS; then
        echo "没有 iOS 模拟器运行时，先执行：xcodebuild -downloadPlatform iOS" >&2
        exit 1
    fi
    DEST="platform=iOS Simulator,name=${MIM_SIM_NAME:-iPhone 17}"
    EXTRA=()
else
    DEST="generic/platform=iOS"
    EXTRA=(CODE_SIGNING_ALLOWED=NO)
fi

# MIM_USE_IOS_FRAMEWORK=1 会让 SwiftPM 走 binaryTarget（xcframework）而不是 dylib。
MIM_USE_IOS_FRAMEWORK=1 xcodebuild \
    -project "$APP_DIR/MIMiOSApp.xcodeproj" \
    -scheme MIMiOSApp \
    -destination "$DEST" \
    -derivedDataPath "$DERIVED" \
    "${EXTRA[@]}" \
    build

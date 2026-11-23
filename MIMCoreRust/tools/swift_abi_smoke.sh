#!/usr/bin/env bash
#
# 用 Swift 直接调 C ABI，验证「iOS 侧桥接写法」是否正确。
#
# 不需要 Xcode：macOS 自带 swiftc 就能编译运行；iOS 上只是换 target 与链接方式，
# 指针传递、缓冲归还、JSON 字段名这些易错点完全一样。
#
# 用法：MIMCoreRust/tools/swift_abi_smoke.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUST_DIR="$ROOT/MIMCoreRust"
OUT_DIR="$RUST_DIR/target/swift-smoke"
LIB_DIR="$RUST_DIR/target/release"
BIN="$OUT_DIR/abi_smoke"

if [[ ! -f "$LIB_DIR/libmim_core_rust.dylib" ]]; then
    echo "缺少动态库，先跑：cargo build --release（在 MIMCoreRust 下）" >&2
    exit 1
fi

mkdir -p "$OUT_DIR"
echo "编译 Swift 冒烟程序…"
swiftc -O \
    -import-objc-header "$RUST_DIR/include/mim_core.h" \
    -I "$RUST_DIR/include" \
    -L "$LIB_DIR" -lmim_core_rust \
    -Xlinker -rpath -Xlinker "$LIB_DIR" \
    -o "$BIN" "$RUST_DIR/tools/swift/abi_smoke.swift"

echo "运行…"
"$BIN"

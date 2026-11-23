#!/usr/bin/env bash
#
# 把 Rust 的 C ABI 头文件同步进 SwiftPM 的 systemLibrary 目标。
#
# SwiftPM 的 systemLibrary 只能引用包内的头文件路径，所以这里复制一份；
# 复制而不是符号链接，是为了让包在 clone 后不依赖仓库外的相对布局。
#
# 用法：
#   MIMiOS/MIMKit/tools-sync-header.sh          # 同步
#   MIMiOS/MIMKit/tools-sync-header.sh --check  # 只检查是否已同步（CI 用）
set -euo pipefail

PKG="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$PKG/../../MIMCoreRust/include/mim_core.h"
DST="$PKG/Sources/MIMCoreABI/include/mim_core.h"

if [[ ! -f "$SRC" ]]; then
    echo "找不到源头文件：$SRC" >&2
    exit 1
fi

if [[ "${1:-}" == "--check" ]]; then
    if diff -q "$SRC" "$DST" >/dev/null 2>&1; then
        echo "头文件已同步"
        exit 0
    fi
    echo "头文件与 MIMCoreRust/include/mim_core.h 不一致，请运行 $0" >&2
    exit 1
fi

mkdir -p "$(dirname "$DST")"
cp "$SRC" "$DST"
echo "已同步头文件：$DST"

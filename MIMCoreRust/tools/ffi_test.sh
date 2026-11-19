#!/usr/bin/env bash
#
# C ABI 冒烟测试：链接 Rust 静态库，跑完整会话与状态恢复。
#
# 这是 Android JNI 与 iOS cinterop 的最小调用样例；两边语言不同，
# 但都经过同一份头文件 include/mim_core.h。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUST_DIR="$ROOT_DIR/MIMCoreRust"
WORK_DIR="${1:-/tmp/mim-ffi}"
CC="${CC:-clang}"

if ! command -v cargo >/dev/null 2>&1; then
  export PATH="$HOME/.cargo/bin:$PATH"
fi
command -v cargo >/dev/null 2>&1 || { echo "需要 cargo（Rust 工具链）" >&2; exit 1; }
command -v "$CC" >/dev/null 2>&1 || { echo "需要 C 编译器（CC=$CC）" >&2; exit 1; }

mkdir -p "$WORK_DIR"

echo "== 构建 Rust 静态库（带 JNI 特性，避免与 jni_test.sh 互相覆盖） =="
(cd "$RUST_DIR" && cargo build --release --features jni --quiet)

if [ "$(uname -s)" = "Darwin" ]; then
  EXTRA_LIBS=""
else
  EXTRA_LIBS="-lpthread -ldl -lm"
fi

echo "== 编译并运行 C 冒烟测试 =="
# shellcheck disable=SC2086
"$CC" -O2 -Wall -Wextra -I "$RUST_DIR/include" \
  "$RUST_DIR/tests/c/ffi_test.c" \
  "$RUST_DIR/target/release/libmim_core_rust.a" \
  $EXTRA_LIBS -o "$WORK_DIR/mim_ffi_test"

"$WORK_DIR/mim_ffi_test"

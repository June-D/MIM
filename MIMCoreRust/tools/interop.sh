#!/usr/bin/env bash
#
# Rust ⇄ Java 真实互通联调。
#
# 流程：
#   1. Rust 作为发起方完成 X3DH 并加密首条消息
#   2. Java（MIMCore 的 RatchetService）作为接收方解密并回信
#   3. Rust 解密 Java 的回信
#
# 两边各自独立实现协议，只共享线上格式；任一步失败即退出非零。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CORE_DIR="$ROOT_DIR/MIMCore"
RUST_DIR="$ROOT_DIR/MIMCoreRust"
WORK_DIR="${1:-/tmp/mim-interop}"
# 只需要 JDK 17：MIMCore 以 source/target 1.8 编译，可运行在 17 上。
if [ -x "${CLIENT_JAVA_HOME:-}/bin/javac" ]; then
  export JAVA_HOME="$CLIENT_JAVA_HOME"
else
  export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || true)}"
fi
export PATH="$JAVA_HOME/bin:$PATH"

if ! command -v cargo >/dev/null 2>&1; then
  export PATH="$HOME/.cargo/bin:$PATH"
fi
command -v cargo >/dev/null 2>&1 || { echo "需要 cargo（Rust 工具链）" >&2; exit 1; }

rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR"

echo "== 1/3 Rust 发起方建立会话并加密首条消息 =="
(cd "$RUST_DIR" && cargo run --quiet --bin interop -- init "$WORK_DIR")

echo "== 2/3 Java 接收方解密并回信 =="
(
  cd "$CORE_DIR"
  if [ ! -d target/classes ]; then
    "$ROOT_DIR/mvnw" -q -o compile || "$ROOT_DIR/mvnw" -q compile
  fi
  "$ROOT_DIR/mvnw" -q -o dependency:build-classpath -Dmdep.outputFile="$WORK_DIR/core-cp.txt" \
    || "$ROOT_DIR/mvnw" -q dependency:build-classpath -Dmdep.outputFile="$WORK_DIR/core-cp.txt"
  CP="target/classes:$(cat "$WORK_DIR/core-cp.txt")"
  mkdir -p "$WORK_DIR/java-classes"
  javac -encoding UTF-8 -cp "$CP" -d "$WORK_DIR/java-classes" tools/InteropResponder.java
  java -cp "$WORK_DIR/java-classes:$CP" com.junesoon.mim.crypto.InteropResponder "$WORK_DIR"
)

echo "== 3/3 Rust 解密 Java 回信 =="
(cd "$RUST_DIR" && cargo run --quiet --bin interop -- finish "$WORK_DIR")

echo
echo "互通联调通过：Rust → Java → Rust"

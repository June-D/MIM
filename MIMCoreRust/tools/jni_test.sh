#!/usr/bin/env bash
#
# JNI 绑定冒烟测试（桌面 JVM）。
#
# 构建带 jni 特性的 cdylib，编译 Java 类，运行完整会话与状态恢复。
# Android 侧只需把 .so 放进 jniLibs 并保留同一个 Java 类。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUST_DIR="$ROOT_DIR/MIMCoreRust"
WORK_DIR="${1:-/tmp/mim-jni}"

if ! command -v cargo >/dev/null 2>&1; then
  export PATH="$HOME/.cargo/bin:$PATH"
fi
command -v cargo >/dev/null 2>&1 || { echo "需要 cargo（Rust 工具链）" >&2; exit 1; }

if [ -x /Users/zhd/jdk/zulu8.98.0.111-ca-fx-jdk8.0.512-macosx_aarch64/Contents/Home/bin/javac ]; then
  export JAVA_HOME="/Users/zhd/jdk/zulu8.98.0.111-ca-fx-jdk8.0.512-macosx_aarch64/Contents/Home"
else
  export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home -v 8 2>/dev/null || true)}"
fi
export PATH="$JAVA_HOME/bin:$PATH"
command -v javac >/dev/null 2>&1 || { echo "需要 JDK（javac）" >&2; exit 1; }

mkdir -p "$WORK_DIR/classes"

echo "== 构建带 JNI 的 Rust cdylib =="
(cd "$RUST_DIR" && cargo build --release --features jni --quiet)

echo "== 编译 Java 绑定与测试 =="
javac -encoding UTF-8 -d "$WORK_DIR/classes"   "$RUST_DIR/java/com/junesoon/mim/core/MimCoreNative.java" \
  "$RUST_DIR/java/com/junesoon/mim/core/MimCoreNativeTest.java"

echo "== 运行 JNI 冒烟测试 =="
java -Djava.library.path="$RUST_DIR/target/release" \
  -cp "$WORK_DIR/classes" com.junesoon.mim.core.MimCoreNativeTest

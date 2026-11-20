#!/usr/bin/env bash
#
# 启动桌面客户端（真实后端）。
#
# 用法：
#   ./run-desktop.sh                 # 连接 http://127.0.0.1:8080
#   ./run-desktop.sh http://host:port
#   ./run-desktop.sh --check         # 只做环境检查与构建，不启动界面
#
# 脚本负责三件事：构建带 JNI 的 Rust 动态库、检查服务端可达、把两项环境
# 变量传给 Gradle。密码学全部在 Rust 核心里，Kotlin 只做传输与 UI。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK_ONLY=0
SERVER_URL="http://127.0.0.1:8080"

for arg in "$@"; do
    case "$arg" in
        --check) CHECK_ONLY=1 ;;
        -h|--help) sed -n '2,12p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) SERVER_URL="$arg" ;;
    esac
done

# 1) 动态库：必须带 jni 特性，否则 Kotlin 找不到 JNI 符号。
LIB="$ROOT/MIMCoreRust/target/release/libmim_core_rust.dylib"
if [[ "$(uname -s)" == "Linux" ]]; then
    LIB="$ROOT/MIMCoreRust/target/release/libmim_core_rust.so"
fi

if [[ ! -f "$LIB" ]]; then
    echo "构建 Rust 动态库（首次需要几分钟）..."
    (cd "$ROOT/MIMCoreRust" && cargo build --release --features jni)
fi

# 2) 服务端可达性：连不上也照样启动，只是界面里的请求会失败。
CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$SERVER_URL/chat/contacts" || true)"
if [[ "$CODE" == "000" ]]; then
    echo "警告：连不上 ${SERVER_URL}，请先启动服务端。" >&2
else
    echo "服务端可达：${SERVER_URL}（HTTP ${CODE}）"
fi

if [[ "$CHECK_ONLY" == "1" ]]; then
    echo "环境检查完成：$LIB"
    exit 0
fi

# 3) 启动界面。
# Gradle 8.10 的 Kotlin DSL 解析不了 JDK 25 的版本号（报 IllegalArgumentException: 25.x）。
# 本机默认 java 可能是新版本，这里显式挑 17 / 21，避免启动时只看到一行看不懂的报错。
if [[ -z "${JAVA_HOME:-}" ]] && [[ -x /usr/libexec/java_home ]]; then
    CANDIDATE="$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home -v 21 2>/dev/null || true)"
    if [[ -n "$CANDIDATE" ]]; then
        export JAVA_HOME="$CANDIDATE"
        echo "使用 JDK：$JAVA_HOME"
    fi
fi

cd "$ROOT/MIMCompose"
export MIM_SERVER_URL="$SERVER_URL"
export MIM_NATIVE_LIB="$LIB"
exec ./gradlew :composeApp:run --console=plain

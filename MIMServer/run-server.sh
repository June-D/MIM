#!/usr/bin/env bash
#
# 启动服务端：构建 → 复制到 target 之外 → 等待就绪。
#
# 用法：
#   ./run-server.sh            # 用当前数据库配置启动（默认 127.0.0.1:13306/mim_core）
#   ./run-server.sh --rebuild  # 强制重新打包
#   ./run-server.sh --stop     # 停止正在运行的服务端
#
# 为什么复制 jar：verify.sh 会在 MIMServer 下执行 clean，
# 正在被 JVM 读取的 target/*.jar 被删掉会让运行中的服务端抛
# NoSuchFileException（表现为登录 500）。复制到 /tmp 可避开。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="$ROOT/MIMServer"
RUN_JAR="/tmp/mim-server.jar"
LOG="/tmp/mim-server.log"
REBUILD=0

for arg in "$@"; do
    case "$arg" in
        --rebuild) REBUILD=1 ;;
        --stop)
            pkill -f "$RUN_JAR" 2>/dev/null || true
            echo "已停止 $RUN_JAR"
            exit 0
            ;;
        -h|--help) sed -n '2,16p' "${BASH_SOURCE[0]}"; exit 0 ;;
    esac
done

if curl -s -o /dev/null --max-time 2 http://127.0.0.1:8080/user/info; then
    echo "8080 已有服务在监听，直接使用现有实例（--stop 可先停止）。"
    exit 0
fi

cd "$SERVER_DIR"
JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home}"
export JAVA_HOME

if [[ "$REBUILD" == "1" || ! -f "$RUN_JAR" ]]; then
    echo "打包服务端…"
    "$ROOT/mvnw" -q -o package -DskipTests
    cp "$SERVER_DIR/target/MIMServer-1.0-SNAPSHOT-exec.jar" "$RUN_JAR"
fi

export MIM_DB_URL="${MIM_DB_URL:-jdbc:mysql://127.0.0.1:13306/mim_core?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf8}"
export MIM_DB_USERNAME="${MIM_DB_USERNAME:-root}"
export MIM_DB_PASSWORD="${MIM_DB_PASSWORD:-}"
export MIM_REDIS_HOST="${MIM_REDIS_HOST:-127.0.0.1}"
export MIM_REDIS_PORT="${MIM_REDIS_PORT:-6379}"
export MIM_SENDER_CERT_ENABLED="${MIM_SENDER_CERT_ENABLED:-true}"
export MIM_SENDER_TOKEN_ENABLED="${MIM_SENDER_TOKEN_ENABLED:-false}"
export MIM_ANONYMOUS_SEND_ENABLED="${MIM_ANONYMOUS_SEND_ENABLED:-false}"

nohup java -jar "$RUN_JAR" > "$LOG" 2>&1 &
PID=$!
echo "服务端启动中（pid=${PID}，日志 ${LOG}）…"

for _ in $(seq 1 40); do
    if [[ "$(curl -s -o /dev/null -m 2 -w '%{http_code}' http://127.0.0.1:8080/user/info || true)" == "401" ]]; then
        echo "服务端已就绪：http://127.0.0.1:8080"
        exit 0
    fi
    sleep 2
done

echo "启动超时，请查看 $LOG" >&2
tail -20 "$LOG" >&2
exit 1

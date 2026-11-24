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
# 日志放持久目录；/tmp 下的名字保留成软链，老的排查习惯不失效。
DATA_DIR="${MIM_DATA_DIR:-$HOME/MIM-data}"
mkdir -p "$DATA_DIR/logs" 2>/dev/null || true
LOG="${MIM_LOG_FILE:-$DATA_DIR/logs/server.log}"
ln -sf "$LOG" /tmp/mim-server.log 2>/dev/null || true
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
# 媒体密文与 TLS 证书都放持久目录：放 /tmp 会随重启消失（已经因此丢过一次库）。
export MIM_MEDIA_DIR="${MIM_MEDIA_DIR:-$DATA_DIR/media}"
export MIM_TLS_KEYSTORE="${MIM_TLS_KEYSTORE:-$DATA_DIR/tls/mim-tls.p12}"
export MIM_TLS_KEYSTORE_PASSWORD="${MIM_TLS_KEYSTORE_PASSWORD:-changeit}"

export MIM_SENDER_CERT_ENABLED="${MIM_SENDER_CERT_ENABLED:-true}"
export MIM_SENDER_TOKEN_ENABLED="${MIM_SENDER_TOKEN_ENABLED:-false}"
export MIM_ANONYMOUS_SEND_ENABLED="${MIM_ANONYMOUS_SEND_ENABLED:-false}"

# 稳定密钥：没有它时服务端会用「RSA 私钥的 SHA-256」当派生密钥，
# 而 RSA 私钥一旦重新生成（例如 t_rsa_key 被清），所有已加密的 TOTP / 邮箱
# 数据都会变成解不开的密文——症状是登录时永远报「二次验证码错误」。
# 邮件通道凭据（阿里云邮件推送等）：放 ~/.mim/smtp.env，权限 600，不入库。
SMTP_ENV="${MIM_SMTP_ENV:-$HOME/.mim/smtp.env}"
if [[ -f "$SMTP_ENV" ]]; then
    set -a
    # shellcheck disable=SC1090
    . "$SMTP_ENV"
    set +a
fi

KEY_DIR="${MIM_KEY_DIR:-$HOME/.mim/keys}"
if [[ -z "${MIM_EMAIL_KEY_FILE:-}" && -f "$KEY_DIR/email.key" ]]; then
    export MIM_EMAIL_KEY_FILE="$KEY_DIR/email.key"
fi
if [[ -z "${MIM_MASTER_KEY_FILE:-}" && -f "$KEY_DIR/master.key" ]]; then
    export MIM_MASTER_KEY_FILE="$KEY_DIR/master.key"
fi

nohup java -jar "$RUN_JAR" > "$LOG" 2>&1 &
PID=$!
echo "服务端启动中（pid=${PID}，日志 ${LOG}）…"

for _ in $(seq 1 40); do
    CODE="$(curl -s -o /dev/null -m 2 -w '%{http_code}' http://127.0.0.1:8080/user/info || true)"
    # 401 = 明文端口正常；403 = 已开 TLS 且按配置拒绝明文（也是「起来了」）。
    if [[ "$CODE" == "401" ]]; then
        echo "服务端已就绪：http://127.0.0.1:8080"
        exit 0
    fi
    if [[ "$CODE" == "403" ]]; then
        echo "服务端已就绪：明文端口按要求返回 403，请改用 https://127.0.0.1:${MIM_TLS_PORT:-8443}"
        exit 0
    fi
    sleep 2
done

echo "启动超时，请查看 $LOG" >&2
tail -20 "$LOG" >&2
exit 1

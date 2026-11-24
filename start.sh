#!/usr/bin/env bash
#
# 一条命令把整套环境拉起来：MySQL → Redis → 服务端（TLS）。
#
# 为什么需要它：之前数据库目录和证书都在 /tmp，重启 Mac 就被清，
# 已经因此丢过一次表（RSA 私钥重新生成 → 二次验证密钥全废）。
# 现在数据一律放 ~/MIM-data，这个脚本负责按新路径启动。
#
# 用法：
#   ./start.sh              # 启动（已在跑的组件会跳过）
#   ./start.sh --status     # 只看状态
#   ./start.sh --stop       # 全部停掉
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA="${MIM_DATA_DIR:-$HOME/MIM-data}"
MYSQL_BIN="${MYSQL_BIN:-/opt/homebrew/opt/mysql/bin}"
DB_PORT="${MIM_DB_PORT:-13306}"
TLS_PORT="${MIM_TLS_PORT:-8443}"
KEYSTORE="$DATA/tls/mim-tls.p12"

port_open() { nc -z 127.0.0.1 "$1" >/dev/null 2>&1; }

status() {
    printf '%-12s %s\n' "MySQL($DB_PORT)" "$(port_open "$DB_PORT" && echo 运行中 || echo 未运行)"
    printf '%-12s %s\n' "Redis(6379)" "$(port_open 6379 && echo 运行中 || echo 未运行)"
    printf '%-12s %s\n' "服务端(${TLS_PORT})" "$(port_open "$TLS_PORT" && echo 运行中 || echo 未运行)"
    printf '%-12s %s\n' "数据目录" "${DATA}"
}

case "${1:-}" in
    --status) status; exit 0 ;;
    --stop)
        "$ROOT/MIMServer/run-server.sh" --stop >/dev/null 2>&1 || true
        "$MYSQL_BIN/mysqladmin" -h127.0.0.1 -P"$DB_PORT" -uroot shutdown 2>/dev/null || true
        echo "已停止服务端与 MySQL（Redis 用 brew services stop redis 停）"
        exit 0
        ;;
esac

mkdir -p "$DATA"/{mysql,tls,media,logs}

# 1) MySQL：数据目录在 ~/MIM-data/mysql，端口 13306
if ! port_open "$DB_PORT"; then
    if [[ ! -d "$DATA/mysql/mysql" ]]; then
        echo "首次初始化 MySQL 数据目录…"
        "$MYSQL_BIN/mysqld" --initialize-insecure --datadir="$DATA/mysql" \
            --basedir=/opt/homebrew/opt/mysql >/dev/null 2>&1
    fi
    echo "启动 MySQL（datadir=${DATA}/mysql）…"
    "$MYSQL_BIN/mysqld" --daemonize \
        --datadir="$DATA/mysql" \
        --basedir=/opt/homebrew/opt/mysql \
        --port="$DB_PORT" \
        --socket="$DATA/mysql/mysql.sock" \
        --pid-file="$DATA/mysql/mysqld.pid" \
        --log-error="$DATA/logs/mysql.err" \
        --mysqlx=0
    for _ in $(seq 1 30); do port_open "$DB_PORT" && break; sleep 1; done
fi

# 2) Redis：推送走 Pub/Sub，缺了它跨实例推送失效（单实例也会告警）
if ! port_open 6379; then
    if command -v brew >/dev/null && brew services list 2>/dev/null | grep -q "^redis"; then
        brew services start redis >/dev/null 2>&1 || true
    else
        redis-server --daemonize yes >/dev/null 2>&1 || true
    fi
    for _ in $(seq 1 15); do port_open 6379 && break; sleep 1; done
fi

# 3) 邮件通道（阿里云邮件推送等）：凭据放 ~/.mim/smtp.env，不进仓库
SMTP_ENV="${MIM_SMTP_ENV:-$HOME/.mim/smtp.env}"
if [[ -f "$SMTP_ENV" ]]; then
    set -a
    # shellcheck disable=SC1090
    . "$SMTP_ENV"
    set +a
fi

# 4) 服务端：TLS + 数据目录 + 稳定密钥
if ! port_open "$TLS_PORT"; then
    if [[ ! -f "$KEYSTORE" ]]; then
        echo "缺少 TLS 证书：${KEYSTORE}" >&2
        echo "（重新生成见 MIMiOS/README；或用 MIM_TLS_KEYSTORE 指定）" >&2
        exit 1
    fi
    echo "启动服务端（TLS :${TLS_PORT}）…"
    MIM_TLS_ENABLED=true \
    MIM_TLS_PORT="$TLS_PORT" \
    MIM_TLS_KEYSTORE="$KEYSTORE" \
    MIM_TLS_KEYSTORE_PASSWORD="${MIM_TLS_KEYSTORE_PASSWORD:-changeit}" \
    MIM_MEDIA_DIR="$DATA/media" \
    MIM_LOG_FILE="$DATA/logs/server.log" \
    "$ROOT/MIMServer/run-server.sh" "${MIM_SERVER_ARGS:-}" >/dev/null
fi

sleep 2
status
echo
echo "服务端：https://127.0.0.1:${TLS_PORT}"
echo "日志：  ${DATA}/logs/server.log"
echo "数据：  ${DATA}（mysql / tls / media）"

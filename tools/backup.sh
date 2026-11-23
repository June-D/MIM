#!/usr/bin/env bash
# MIM 备份：数据库 + 加密媒体目录。
#
# 用法：
#   tools/backup.sh [输出目录]        # 默认 ./backups
#
# 环境变量（默认值对应文档里的本地部署）：
#   MIM_DB_HOST=127.0.0.1  MIM_DB_PORT=3306  MIM_DB_NAME=mim_core
#   MIM_DB_USERNAME=root   MIM_DB_PASSWORD=
#   MIM_MEDIA_DIR=MIMServer/data/media
#
# 说明：密钥（MIM_MASTER_KEY / MIM_EMAIL_KEY / TLS keystore）**不在**备份里，
# 它们在环境变量或外部密钥管理服务中，必须单独保管；丢了这两把钥匙，
# 备份里的 TOTP / 邮箱密文就再也解不开，服务端也会拒绝启动。
set -euo pipefail

OUT_ROOT="${1:-./backups}"
DB_HOST="${MIM_DB_HOST:-127.0.0.1}"
DB_PORT="${MIM_DB_PORT:-3306}"
DB_NAME="${MIM_DB_NAME:-mim_core}"
DB_USER="${MIM_DB_USERNAME:-root}"
DB_PASS="${MIM_DB_PASSWORD:-}"
MEDIA_DIR="${MIM_MEDIA_DIR:-MIMServer/data/media}"

command -v mysqldump >/dev/null || { echo "缺少 mysqldump（MySQL 客户端工具）" >&2; exit 1; }
command -v tar >/dev/null || { echo "缺少 tar" >&2; exit 1; }

STAMP="$(date +%Y%m%d-%H%M%S)"
DIR="${OUT_ROOT%/}/mim-${STAMP}"
mkdir -p "$DIR"
chmod 700 "$DIR"

echo "备份到 ${DIR}"

# 1) 数据库：单事务导出，保证一致性；带上例程与触发器。
MYSQL_PWD="$DB_PASS" mysqldump \
  --single-transaction --quick --routines --triggers --set-gtid-purged=OFF \
  --default-character-set=utf8mb4 \
  -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" "$DB_NAME" > "$DIR/db.sql"

# 2) 媒体密文：服务端只存密文，但丢了这些，历史图片/语音/文件就再也取不回。
if [ -d "$MEDIA_DIR" ]; then
  tar -czf "$DIR/media.tar.gz" -C "$(dirname "$MEDIA_DIR")" "$(basename "$MEDIA_DIR")"
  echo "已归档媒体目录：$MEDIA_DIR"
else
  echo "媒体目录不存在，跳过：$MEDIA_DIR"
fi

# 3) 校验和：恢复前先核对，避免拿到半截文件还以为备份成功。
(
  cd "$DIR"
  sha256sum ./* > SHA256SUMS
)

# 4) 恢复说明与密钥清单：跟备份放在一起，避免「有备份但不知道怎么恢复」。
cat > "$DIR/RESTORE.md" <<EOF
# MIM 备份 ${STAMP}

## 内容
- \`db.sql\`：MySQL \`${DB_NAME}\` 全量导出（表结构 + 数据，单事务一致性快照）
- \`media.tar.gz\`：加密媒体目录（${MEDIA_DIR}）
- \`SHA256SUMS\`：校验和

## 恢复步骤
1. 校验：\`cd <本目录> && sha256sum -c SHA256SUMS\`
2. 建库并导入：
   \`\`\`bash
   mysql -h<host> -u<user> -p -e "CREATE DATABASE IF NOT EXISTS ${DB_NAME} CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
   mysql -h<host> -u<user> -p ${DB_NAME} < db.sql
   \`\`\`
3. 还原媒体：\`tar -xzf media.tar.gz -C <MIMServer 所在目录的上一级>\`
4. **先用同一个 MIM_MASTER_KEY / MIM_EMAIL_KEY 启动**，确认能登录、能看到 TOTP 与找回邮箱；
   再按需轮换。
5. 在隔离环境做一次完整演练（登录 → 发一条消息 → 收一条消息 → 打开旧媒体的附件），
   确认无误后再销毁演练环境。

## 另行保管（不在备份内）
- \`MIM_MASTER_KEY\`（缺失会导致服务端拒绝启动）
- \`MIM_EMAIL_KEY\`（缺失会导致 TOTP / 找回邮箱密文无法解密）
- TLS keystore 与口令（\`MIM_TLS_KEYSTORE\` / \`MIM_TLS_KEYSTORE_PASSWORD\`）

> 备份文件里全是密文，但仍然包含账号、设备公钥与收发元数据：请按敏感数据处理，
> 不要提交进仓库，也不要放在公共目录。
EOF

echo "完成："
du -sh "$DIR"/* 2>/dev/null || true
echo "记得另行保管 MIM_MASTER_KEY / MIM_EMAIL_KEY / TLS keystore —— 它们不在这份备份里。"

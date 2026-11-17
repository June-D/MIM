#!/usr/bin/env bash
#
# 一键全量验证：编译 + 单元测试 + （可选）端到端回归。
#
# 用法：
#   ./verify.sh              仅编译与单元测试
#   ./verify.sh --e2e        额外跑端到端回归（需要可连接的 MySQL/Redis）
#
# 环境要求：
#   MIM_SERVER_JAVA_HOME  服务端 JDK 8（Corretto 等，无需 JavaFX）
#   MIM_CLIENT_JAVA_HOME  客户端 JDK 8（必须带 JavaFX，如 Zulu FX 8）
#
# 未设置 JAVA_HOME 时脚本会尝试常见路径，找不到则明确报错而不是静默跳过。

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$ROOT_DIR/MIMServer"
CLIENT_DIR="$ROOT_DIR/MIMClient"

RUN_E2E=0
for arg in "$@"; do
  case "$arg" in
    --e2e) RUN_E2E=1 ;;
    -h|--help)
      sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "未知参数：$arg（用 --help 查看用法）" >&2; exit 2 ;;
  esac
done

FAILURES=0

section() {
  echo
  echo "=============================================================="
  echo "  $1"
  echo "=============================================================="
}

fail() {
  echo "  ✗ $1" >&2
  FAILURES=$((FAILURES + 1))
}

ok() {
  echo "  ✓ $1"
}

# ---------------- 定位 JDK ----------------

pick_java_home() {
  local explicit="$1"
  shift
  if [ -n "$explicit" ] && [ -x "$explicit/bin/java" ]; then
    echo "$explicit"
    return 0
  fi
  local candidate
  for candidate in "$@"; do
    if [ -x "$candidate/bin/java" ]; then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}

SERVER_JH="$(pick_java_home "${MIM_SERVER_JAVA_HOME:-}" \
  "$HOME/Library/Java/JavaVirtualMachines/corretto-1.8.0_472/Contents/Home" \
  "/Library/Java/JavaVirtualMachines/1.8.0.jdk/Contents/Home" \
  "/usr/lib/jvm/java-8-openjdk-amd64" \
  "/usr/lib/jvm/zulu8")" || SERVER_JH=""

CLIENT_JH="$(pick_java_home "${MIM_CLIENT_JAVA_HOME:-}" \
  "$HOME/jdk/zulu8.98.0.111-ca-fx-jdk8.0.512-macosx_aarch64/Contents/Home" \
  "$HOME/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home" \
  "/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home")" || CLIENT_JH=""

MISSING_JAVA=0
if [ -z "$SERVER_JH" ]; then
  fail "找不到服务端 JDK 8，请设置 MIM_SERVER_JAVA_HOME"
  MISSING_JAVA=1
else
  ok "服务端 JDK：${SERVER_JH}（$("$SERVER_JH/bin/java" -version 2>&1 | head -1)）"
fi

if [ -z "$CLIENT_JH" ]; then
  fail "找不到带 JavaFX 的客户端 JDK 8，请设置 MIM_CLIENT_JAVA_HOME"
  MISSING_JAVA=1
else
  if [ ! -f "${CLIENT_JH}/jre/lib/ext/jfxrt.jar" ] && [ ! -f "${CLIENT_JH}/lib/ext/jfxrt.jar" ]; then
    fail "客户端 JDK 缺少 JavaFX（未找到 jfxrt.jar）：${CLIENT_JH}"
    MISSING_JAVA=1
  else
    ok "客户端 JDK：${CLIENT_JH}（含 JavaFX）"
  fi
fi

if [ "$MISSING_JAVA" -ne 0 ]; then
  echo
  echo "JDK 环境不完整，后续步骤无法执行。" >&2
  echo "客户端需要带 JavaFX 的 JDK 8，下载方式见 README。" >&2
  exit 1
fi

# ---------------- 服务端 ----------------

section "服务端：编译 + 单元测试"
(
  cd "$SERVER_DIR" || exit 1
  export JAVA_HOME="$SERVER_JH"
  export PATH="$JAVA_HOME/bin:$PATH"
  mvn -q -o clean compile
) && ok "编译通过" || fail "服务端编译失败"

(
  cd "$SERVER_DIR" || exit 1
  export JAVA_HOME="$SERVER_JH"
  export PATH="$JAVA_HOME/bin:$PATH"
  # 集成测试需要真实 MySQL。未提供时相关用例会被自动跳过（Assumptions），
  # 而不是失败或假通过。
  # 若未显式设置，尝试使用 .local 脚本约定的默认测试库。
  if [ -n "${MIM_TEST_DB_URL:-}" ]; then
    echo "  集成测试数据库：$MIM_TEST_DB_URL"
  else
    echo "  未设置 MIM_TEST_DB_URL，集成测试将跳过（Mapper / HTTP 层）"
  fi
  mvn -o test 2>&1 | tail -30
) || fail "服务端测试失败"

# ---------------- 客户端 ----------------

section "客户端：编译 + 单元测试"
(
  cd "$CLIENT_DIR" || exit 1
  export JAVA_HOME="$CLIENT_JH"
  export PATH="$JAVA_HOME/bin:$PATH"
  mvn -q -o clean compile
) && ok "编译通过" || fail "客户端编译失败"

(
  cd "$CLIENT_DIR" || exit 1
  export JAVA_HOME="$CLIENT_JH"
  export PATH="$JAVA_HOME/bin:$PATH"
  mvn -o test 2>&1 | tail -30
) || fail "客户端测试失败"

# ---------------- 端到端（可选） ----------------

if [ "$RUN_E2E" -eq 1 ]; then
  section "端到端回归（需要运行中的 MySQL/Redis/服务端）"

  # 前置检查：端到端依赖外部服务。缺失时明确报错，而不是跑出一堆 Connection refused。
  E2E_BASE="${MIM_BASE:-http://127.0.0.1:8080}"
  PREFLIGHT_OK=1
  # 服务端就绪判定：未认证接口应返回 401（说明 Web 层已起来）
  SERVER_CODE="$(curl -s -o /dev/null -m 3 -w '%{http_code}' "$E2E_BASE/user/info" 2>/dev/null || true)"
  if [ "$SERVER_CODE" != "401" ]; then
    echo "  ✗ 服务端未就绪：$E2E_BASE/user/info 返回 '$SERVER_CODE'（期望 401）" >&2
    echo "    请先启动服务端再运行 --e2e" >&2
    PREFLIGHT_OK=0
  fi
  if command -v redis-cli >/dev/null 2>&1; then
    if ! redis-cli ping >/dev/null 2>&1; then
      echo "  ✗ Redis 未响应（端到端需要它验证限流与跨实例推送）" >&2
      PREFLIGHT_OK=0
    fi
  fi

  if [ "$PREFLIGHT_OK" -eq 0 ]; then
    fail "端到端前置检查未通过（跳过执行）"
  else
    # 清掉历史限流计数：端到端会连续注册多个账号，跨轮次会撞上 5 次/分钟的限制
    redis-cli --scan --pattern "mim:ratelimit:*" 2>/dev/null | xargs -r redis-cli del >/dev/null 2>&1

    # 编译并运行端到端脚本：它们不在 Maven 默认测试路径内，需要手动编译。
    # 包路径按实际目录推导，避免把类名写死在脚本里。
    TMP_E2E="$(mktemp -d)"
    E2E_OK=1
    for E2E_FILE in "$CLIENT_DIR"/src/test/e2e/*.java; do
      [ -f "$E2E_FILE" ] || continue
      E2E_NAME="$(basename "$E2E_FILE" .java)"
      echo "--- ${E2E_NAME} ---"
      (
        cd "$CLIENT_DIR" || exit 1
        export JAVA_HOME="$CLIENT_JH"
        export PATH="$JAVA_HOME/bin:$PATH"
        mvn -o dependency:build-classpath -Dmdep.outputFile="$TMP_E2E/cp.txt" -q
        CP="target/classes:$(cat "$TMP_E2E/cp.txt")"
        javac -encoding UTF-8 -cp "$CP" -d "$TMP_E2E" "$E2E_FILE"
        # 从源码里的 package 声明取全限定类名
        PKG="$(sed -n 's/^package \([a-zA-Z0-9_.]*\);.*/\1/p' "$E2E_FILE" | head -1)"
        if [ -n "$PKG" ]; then
          java -cp "$TMP_E2E:$CP" "${PKG}.${E2E_NAME}"
        else
          java -cp "$TMP_E2E:$CP" "${E2E_NAME}"
        fi
      ) || E2E_OK=0
    done
    if [ "$E2E_OK" -eq 1 ]; then
      ok "端到端回归通过"
    else
      fail "端到端回归失败"
    fi
    rm -rf "$TMP_E2E"
  fi
else
  echo
  echo "（跳过端到端回归；加 --e2e 参数可启用）"
fi

# ---------------- 汇总 ----------------

section "结果"
if [ "$FAILURES" -eq 0 ]; then
  echo "全部通过"
  exit 0
fi
echo "失败项：$FAILURES" >&2
exit 1

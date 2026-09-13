#!/usr/bin/env bash
# 前端端到端验收（替代已删除的 e2e-frontend.mjs：puppeteer + 本机 Chrome → Playwright 自带 chromium）。
# 用法：SPARK_PORT=8091 bash .harness/scripts/e2e-frontend.sh
#
# 本脚本负责起后端（e2e profile，装配 host-demo 的 fake 规划器），前端 dev server 由 Playwright 的
# webServer 自己拉起（它需要注入 SPARK_BACKEND，所以不能复用已有实例）。
# 前置：spark-rooter/examples/host-demo/target/host-demo.jar 已构建；pnpm -C spark-ui run e2e:install 已执行。
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$ROOT/.harness/scripts/lib/change-dir.sh"
source "$ROOT/.harness/scripts/lib/java-home.sh"
PORT="${SPARK_PORT:-8091}"
FRONT_PORT="${SPARK_FRONT_PORT:-5199}"
JAR="$ROOT/spark-rooter/examples/host-demo/target/host-demo.jar"
OUT="$DEPLOY/e2e-frontend"

# shellcheck disable=SC2317,SC2329  # 由下一行的 trap 调用，shellcheck 不把 trap 算作调用点（0.9 报 2317，0.10+ 改名 2329）
cleanup() { pkill -f "host-demo.jar --server.port=$PORT" 2>/dev/null; }
trap cleanup EXIT

# 前置 1：core dist —— Playwright 的 webServer 走 `pnpm run dev` 会触发 predev 自动构建，
# 但这里先显式检查，好让缺失时的报错落在脚本开头而不是 120s webServer 超时之后。
if [ ! -d "$ROOT/spark-ui/packages/core/dist" ]; then
  echo "spark-ui/packages/core/dist 不存在：先跑 pnpm -C spark-ui run build（或 pnpm -C .harness run ci）"
  exit 2
fi
# 前置 2：后端 jar
if [ ! -f "$JAR" ]; then
  echo "host-demo.jar 不存在：先跑 node .harness/scripts/mvn.mjs -q -B install，再在 examples/host-demo 下 mvn -o package"
  exit 2
fi
# 前置 3：两个端口独占。前端用 strictPort，被占时 Playwright 会直接失败而不是连错实例。
for p in "$PORT" "$FRONT_PORT"; do
  owner=$(lsof -tnP -iTCP:"$p" -sTCP:LISTEN 2>/dev/null | head -1)
  if [ -n "$owner" ]; then
    echo "port $p is held by PID $owner: $(ps -o command= -p "$owner" | cut -c1-80)"
    echo "e2e-frontend needs exclusive ports; stop it or set SPARK_PORT / SPARK_FRONT_PORT, then rerun."
    exit 2
  fi
done

echo "--- 1. 后端（e2e profile，fake 规划器）"
(JAVA_HOME="$JAVA_HOME_RESOLVED" "$JAVA_BIN" -jar "$JAR" --server.port="$PORT" --spring.profiles.active=e2e > "$DEPLOY/e2e-frontend-backend.log" 2>&1 &)
for _ in $(seq 1 40); do sleep 1; curl -sf "localhost:$PORT/actuator/health" >/dev/null 2>&1 && break; done
if ! curl -sf "localhost:$PORT/actuator/health" >/dev/null 2>&1; then
  echo "BACKEND BOOT FAILED"; tail -20 "$DEPLOY/e2e-frontend-backend.log"; exit 1
fi
echo "  health: $(curl -s "localhost:$PORT/actuator/health")"
echo "  planner: $(grep -c '假规划器已装配' "$DEPLOY/e2e-frontend-backend.log") (1 = fake-e2e)"

echo "--- 2. Playwright（自带 chromium，dev server 由 webServer 拉起于 ${FRONT_PORT}）"
rm -rf "$OUT"
SPARK_FRONT_PORT="$FRONT_PORT" SPARK_BACKEND="http://localhost:$PORT" \
  pnpm -C "$ROOT/spark-ui" run e2e
rc=$?

echo "--- 3. 收集报告到 $OUT"
mkdir -p "$OUT"
[ -d "$ROOT/spark-ui/e2e-report" ] && cp -R "$ROOT/spark-ui/e2e-report/." "$OUT/"
[ -d "$ROOT/spark-ui/test-results" ] && cp -R "$ROOT/spark-ui/test-results" "$OUT/test-results"
# shellcheck disable=SC2012  # 只是把收集到的文件名打给人看，报告目录里没有特殊字符文件名
echo "  $(ls "$OUT" | tr '\n' ' ')"

exit $rc

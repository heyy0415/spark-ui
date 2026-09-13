#!/usr/bin/env bash
# 阶段 7 deploy-verify（deploy-verify Skill 脚本化）：一次性生成并冻结 deployment/ 全部产物。
# 前置：pnpm -C .harness run ci 已通过（dist 与 host-demo.jar 为最新）。用法：bash .harness/scripts/deploy-verify.sh
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$ROOT/.harness/scripts/lib/change-dir.sh"
P="$ROOT/.harness/scripts/sse-parse.mjs"
source "$ROOT/.harness/scripts/lib/java-home.sh"
pass=0; fail=0
check() { if [ "$2" = "$3" ]; then echo "  ✓ $1: $3"; pass=$((pass+1)); else echo "  ✗ $1: expected [$2] got [$3]"; fail=$((fail+1)); fi; }
# 后端端口可用 SPARK_PORT 覆盖（默认 8080）；vite preview 经 SPARK_BACKEND 代理到它
PORT="${SPARK_PORT:-8080}"
# 只清理本脚本自己起的实例（带 --server.port=$PORT）与 4173 预览，不碰 IDE 里手动启动的
cleanup() { pkill -f "examples/host-demo/target/host-demo.jar --server.port=$PORT" 2>/dev/null; pkill -f "vite preview --port 4173 --strictPort" 2>/dev/null; }
trap cleanup EXIT
cleanup; sleep 1
export SPARK_BACKEND="http://localhost:$PORT"
# 前置：后端端口 / 4173 不得被本脚本之外的进程占用（如 IDEA 里手动启动的后端）。否则会误对着别人的实例、别人的内存状态验收。
for port in "$PORT" 4173; do
  owner=$(lsof -tnP -iTCP:"$port" -sTCP:LISTEN 2>/dev/null | head -1)
  if [ -n "$owner" ]; then
    cmd=$(ps -o command= -p "${owner}" | cut -c1-80)
    echo "port ${port} is held by PID ${owner}: ${cmd}"
    echo "deploy-verify needs exclusive ports; stop that process (IDEA stop button, or kill ${owner}) or set SPARK_PORT, and rerun."
    exit 2
  fi
done

echo "--- 1. 后端启动与健康"
# 有模型时验生产形态；无模型时启用 e2e profile 的 fake 规划器，否则「端到端一条 Run」4 条断言
# 必失（UnavailablePlanner 让任何请求直接失败），阶段 7 门禁在无模型机器上不可达。
# 无论走哪条路径都打印实际规划器，报告不掩盖来源。
PROFILE_ARG=""
if [ -z "${SPARK_LLM_API_KEY:-}" ]; then
  PROFILE_ARG="--spring.profiles.active=e2e"
  echo "  planner=fake-e2e（无 SPARK_LLM_API_KEY，启用 e2e profile 的假规划器）"
else
  echo "  planner=llm（生产形态，真实模型）"
fi
(JAVA_HOME="$JAVA_HOME_RESOLVED" "$JAVA_BIN" -jar "$ROOT/spark-rooter/examples/host-demo/target/host-demo.jar" --server.port="$PORT" $PROFILE_ARG > "$DEPLOY/backend.log" 2>&1 &)
for _ in $(seq 1 40); do sleep 1; curl -sf "localhost:$PORT/actuator/health" >/dev/null 2>&1 && break; done
# 只看 status 字段：开了 probes / show-details 后响应还带 groups 与 components，整串比对会被无关字段打红
check "health" UP "$(curl -s "localhost:$PORT/actuator/health" | python3 -c "import sys,json;print(json.load(sys.stdin)['status'])")"
check "selfcheck all OK" 9 "$(grep -c 'SelfCheckRunner.*selfcheck: .* OK' "$DEPLOY/backend.log")"
# readiness 分组含 sparkRooter 指示器（feat-production-hardening T05）：fake planner 的 name() 不是 unavailable → UP
check "readiness group is UP" 200 "$(curl -s -o "$DEPLOY/readiness.json" -w '%{http_code}' "localhost:$PORT/actuator/health/readiness")"
check "readiness includes sparkRooter=UP" UP "$(python3 -c "import json;print(json.load(open('$DEPLOY/readiness.json'))['components']['sparkRooter']['status'])" 2>/dev/null)"

echo "--- 2. 前端预览（vite preview :4173，代理到 ${PORT}）"
(cd "$ROOT/spark-ui/apps/chat" && pnpm exec vite preview --port 4173 --strictPort > "$DEPLOY/preview.log" 2>&1 &)
for _ in $(seq 1 20); do sleep 1; curl -sf localhost:4173/ >/dev/null 2>&1 && break; done
check "preview /" 200 "$(curl -s -o /dev/null -w '%{http_code}' localhost:4173/)"
check "preview proxies /actuator/health" 200 "$(curl -s -o /dev/null -w '%{http_code}' localhost:4173/actuator/health)"

echo "--- 3. 端到端一条 Run（经预览代理）"
curl -s -N --max-time 8 -X POST localhost:4173/agent/runs -H 'Content-Type: application/json' -H 'X-Trace-Id: trace_deploy' --data @"$ROOT/.harness/contracts/examples/intent-request.example.json" > "$DEPLOY/run_events.log"
check "event sequence" "run.started tool.selected tool.started tool.completed tool.selected tool.started tool.completed ui.replace confirmation.required" "$(node "$P" "$DEPLOY/run_events.log" --events)"
RUNID=$(node "$P" "$DEPLOY/run_events.log" --data run.started | python3 -c "import sys,json;print(json.load(sys.stdin)['runId'])")
TOKEN=$(node "$P" "$DEPLOY/run_events.log" --data ui.replace | python3 -c "import sys,json;d=json.load(sys.stdin);print([a for a in d['ui']['actions'] if a['id']=='confirm-refund'][0]['confirmationToken'])")
curl -s -N --max-time 8 -X POST "localhost:4173/agent/runs/$RUNID/actions/confirm-refund" -H 'Content-Type: application/json' -H 'X-Trace-Id: trace_deploy' -d "{\"confirmationToken\":\"$TOKEN\",\"formData\":{\"reason\":\"DAMAGED\"}}" > "$DEPLOY/confirm_events.log"
check "confirm reaches run.completed" 1 "$(node "$P" "$DEPLOY/confirm_events.log" --events | grep -c 'run.completed$')"
curl -s "localhost:4173/agent/runs/$RUNID" > "$DEPLOY/run_summary_done.json"
check "run-summary state" COMPLETED "$(python3 -c "import json;print(json.load(open('$DEPLOY/run_summary_done.json'))['state'])")"
check "backend.log has this run" 1 "$(grep -c "plan attached runId=$RUNID" "$DEPLOY/backend.log")"
check "user text in log" 0 "$(grep -c '帮我把订单 10001 退款' "$DEPLOY/backend.log")"

echo "--- 4. 预览页面 console.error（Playwright chromium）"
node "$ROOT/.harness/scripts/preview-console.mjs" "$DEPLOY" 2>&1 | tee "$DEPLOY/preview-console.log"; rc=${PIPESTATUS[0]}
check "preview pages console.error == 0" 0 "$rc"
check "preview chat page renders #agent-input" "agent-input=1" "$(grep -o 'agent-input=[01]' "$DEPLOY/preview-console.log" | head -1)"

echo "--- 5. 体积报告"
# shellcheck disable=SC2012  # 需要 ls -la 的字节数列喂给 awk；构建产物文件名由 vite 生成，无特殊字符
{ echo "# bundle_size (bytes  path)"; echo "## apps/chat"; ls -la "$ROOT"/spark-ui/apps/chat/dist/assets/*.js | awk '{print $5, $9}' | sort -n | tail -8; echo "## packages/core"; find "$ROOT/spark-ui/packages/core/dist" -name '*.js' -exec ls -la {} + | awk '{print $5, $9}' | sort -n | tail -4; echo "## spark-rooter"; ls -la "$ROOT"/spark-rooter/examples/host-demo/target/host-demo.jar | awk '{print $5, $9}'; } > "$DEPLOY/bundle_size.txt"
check "bundle_size.txt written" 1 "$([ -s "$DEPLOY/bundle_size.txt" ] && echo 1 || echo 0)"

echo; echo "deploy-verify: $pass passed, $fail failed"
[ "$fail" -eq 0 ]

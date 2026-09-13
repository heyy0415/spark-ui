#!/usr/bin/env bash
# 多副本端到端：两个 hub 共享一个 Redis，证明「在 A 发起，在 B 确认」可用（feat-production-hardening T08）。
# 用法：bash .harness/scripts/e2e-multi-instance.sh
#
# 前置：
#   1. 本机 Redis 在 127.0.0.1:6379（redis-cli ping 通）；脚本用 db 15 并在启动前 FLUSHDB
#   2. spark-rooter/ 已 install；examples/host-demo 已 package（host-demo 引了 spark-rooter-redis）
#
# 验的只有跨副本那一段：Run / 令牌 / 幂等 / 记忆四个存储在 Redis 里对两个进程一致。
# 单体链路由 e2e-backend.sh 验，这里不重复。
#
# 写法注意：变量一律 ${VAR}。首版 `$REDIS_DB；`（全角分号紧跟）被 bash 当成变量名 `REDIS_DB；`，set -u 报 unbound；
# 静态检查（ShellCheck）对紧邻非 ASCII 字符的变量名不报，这是它的已知盲区。
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$ROOT/.harness/scripts/lib/change-dir.sh"
source "$ROOT/.harness/scripts/lib/java-home.sh"
P="$ROOT/.harness/scripts/sse-parse.mjs"

A_PORT="${SPARK_HUB_A_PORT:-8095}"
B_PORT="${SPARK_HUB_B_PORT:-8096}"
REDIS_DB=15
A="http://127.0.0.1:$A_PORT"
B="http://127.0.0.1:$B_PORT"
JAR="$ROOT/spark-rooter/examples/host-demo/target/host-demo.jar"
HDR=(-H 'Content-Type: application/json' -H 'X-Trace-Id: trace_multi')
CAP='"clientCapabilities":{"uiSchemaVersion":"1.0","components":["Form","Card","Table","Result","Timeline"]}'

pass=0; fail=0
check() { # $1 name  $2 expected  $3 actual
  if [ "$2" = "$3" ]; then echo "  ✓ $1: $3"; pass=$((pass+1)); else echo "  ✗ $1: expected [$2] got [$3]"; fail=$((fail+1)); fi
}
json() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)"; }
events() { node "$P" "$1" --events; }
data() { node "$P" "$1" --data "$2"; }

if ! redis-cli -n "${REDIS_DB}" ping >/dev/null 2>&1; then
  echo "redis not reachable at 127.0.0.1:6379 (db ${REDIS_DB}); start it and rerun"; exit 2
fi
[ -f "$JAR" ] || { echo "missing $JAR — build it first"; exit 2; }

cleanup() {
  pkill -f "host-demo.jar --server.port=$A_PORT" 2>/dev/null
  pkill -f "host-demo.jar --server.port=$B_PORT" 2>/dev/null
}
trap cleanup EXIT
cleanup; sleep 1
for p in "$A_PORT" "$B_PORT"; do
  owner=$(lsof -tnP -iTCP:"$p" -sTCP:LISTEN 2>/dev/null | head -1)
  [ -z "${owner}" ] || { echo "port $p held by PID $owner; set SPARK_HUB_A_PORT / SPARK_HUB_B_PORT"; exit 2; }
done

redis-cli -n "${REDIS_DB}" FLUSHDB >/dev/null
echo "--- 0. 起两个 hub（profile=e2e,redis；同一 Redis db ${REDIS_DB}；关自检加快启动）"
start_hub() { # $1 port  $2 log
  (JAVA_HOME="$JAVA_HOME_RESOLVED" "$JAVA_BIN" -jar "$JAR" \
     --server.port="$1" \
     --spring.profiles.active=e2e,redis \
     --spring.data.redis.database="${REDIS_DB}" \
     --spark.selfcheck.enabled=false \
     > "$2" 2>&1 &)
}
start_hub "$A_PORT" "$DEPLOY/multi-hub-a.log"
start_hub "$B_PORT" "$DEPLOY/multi-hub-b.log"
for i in $(seq 1 60); do
  sleep 1
  curl -sf "$A/actuator/health" >/dev/null 2>&1 && curl -sf "$B/actuator/health" >/dev/null 2>&1 && break
  if grep -q "Application run failed" "$DEPLOY/multi-hub-a.log" "$DEPLOY/multi-hub-b.log" 2>/dev/null; then
    echo "HUB BOOT FAILED"; grep -m1 -A5 "Application run failed" "$DEPLOY/multi-hub-a.log" "$DEPLOY/multi-hub-b.log"; exit 1
  fi
done
echo "both hubs ready after ${i}s"
check "hub A picked redis storage" 1 "$(grep -c 'spark storage: redis' "$DEPLOY/multi-hub-a.log")"
check "hub B picked redis storage" 1 "$(grep -c 'spark storage: redis' "$DEPLOY/multi-hub-b.log")"
check "no memory-fallback warning" 0 "$(grep -c '已回落到进程内存储' "$DEPLOY/multi-hub-a.log" "$DEPLOY/multi-hub-b.log" | awk -F: '{s+=$2} END{print s}')"

echo "--- 1. 在 A 发起退款（走到确认屏，令牌落 Redis）"
curl -s -N --max-time 8 -X POST "$A/agent/runs" "${HDR[@]}" -d '{"conversationId":"conv_multi","message":"订单 10002 退款",'"$CAP"'}' > "$DEPLOY/multi_run.log"
check "A: reaches confirmation.required" 1 "$(events "$DEPLOY/multi_run.log" | grep -c 'confirmation.required$')"
RUNID=$(data "$DEPLOY/multi_run.log" run.started | json "d['runId']")
TOKEN=$(data "$DEPLOY/multi_run.log" ui.replace | json "[a for a in d['ui']['actions'] if a['id']=='confirm-refund'][0]['confirmationToken']")
echo "  runId=$RUNID token=${TOKEN:0:14}…"
check "redis has the run" 1 "$(redis-cli -n ${REDIS_DB} EXISTS "spark:run:$RUNID")"
check "redis has the token" 1 "$(redis-cli -n ${REDIS_DB} EXISTS "spark:token:$TOKEN")"

echo "--- 2. B 上 GET 同一 runId：状态与屏都来自 Redis，不是 A 的内存"
curl -s "$B/agent/runs/$RUNID" > "$DEPLOY/multi_summary_b.json"
check "B: run-summary state" WAITING_CONFIRMATION "$(json "d['state']" < "$DEPLOY/multi_summary_b.json")"
check "B: run-summary carries currentUi" 1 "$(json "1 if d.get('currentUi') else 0" < "$DEPLOY/multi_summary_b.json")"
check "B: currentUi has confirm-refund action" 1 "$(json "len([a for a in d['currentUi']['actions'] if a['id']=='confirm-refund'])" < "$DEPLOY/multi_summary_b.json")"

echo "--- 3. 在 B 确认：B 凭 Redis 里的 Run 重校验并执行（A 从未把内存交给 B）"
curl -s -N --max-time 8 -X POST "$B/agent/runs/$RUNID/actions/confirm-refund" "${HDR[@]}" -d "{\"confirmationToken\":\"$TOKEN\",\"formData\":{\"reason\":\"DAMAGED\"}}" > "$DEPLOY/multi_confirm.log"
check "B: confirm event sequence" "tool.selected tool.started tool.completed tool.selected tool.started tool.completed ui.replace run.completed" "$(events "$DEPLOY/multi_confirm.log")"
check "B executed refund.create (audit in B log)" 1 "$(grep -c 'toolId=refund.create .*status=succeeded' "$DEPLOY/multi-hub-b.log")"
check "A did not execute refund.create" 0 "$(grep -c 'toolId=refund.create .*status=succeeded' "$DEPLOY/multi-hub-a.log")"
check "token consumed from redis" 0 "$(redis-cli -n ${REDIS_DB} EXISTS "spark:token:$TOKEN")"

echo "--- 4. A 上 GET：看到 B 写回的终态"
check "A: run-summary state after B confirmed" COMPLETED "$(curl -s "$A/agent/runs/$RUNID" | json "d['state']")"

echo "--- 5. 同令牌回 A 重放：Redis 里已 GETDEL → 拒绝，且 Run 仍 COMPLETED"
curl -s -N --max-time 8 -X POST "$A/agent/runs/$RUNID/actions/confirm-refund" "${HDR[@]}" -d "{\"confirmationToken\":\"$TOKEN\",\"formData\":{\"reason\":\"DAMAGED\"}}" > "$DEPLOY/multi_replay.log"
check "A: replay rejected" CONFIRMATION_REJECTED "$(data "$DEPLOY/multi_replay.log" run.failed | json "d['code']")"
check "A: run still COMPLETED" COMPLETED "$(curl -s "$A/agent/runs/$RUNID" | json "d['state']")"

echo "--- 6. 幂等跨副本：对 B 直调 gateway 用 A 执行过的同 key → replayed（不重复执行）"
# 上一步 B 已执行 refund.create，idempotencyKey = {runId}-refund.create-3；scope = sessionId。
# e2e profile 的 DemoSessionIdResolver 把 conversationId 映射为固定 sessionId，两 hub 一致。
SESS=$(grep -o 'sessionId=[^ ]*' "$DEPLOY/multi-hub-b.log" | grep -v selfcheck | head -1 | cut -d= -f2)
IDEM_KEYS=$(redis-cli -n ${REDIS_DB} KEYS "spark:idem:*refund.create*" | wc -l | tr -d ' ')
check "redis holds refund.create idempotency record" 1 "$([ "$IDEM_KEYS" -ge 1 ] && echo 1 || echo 0)"
# 用 A 再调同 key：应命中 Replay，审计口径 replayed，且 refund.create 的 succeeded 总数不变
BEFORE=$(grep -c 'toolId=refund.create .*status=succeeded' "$DEPLOY/multi-hub-a.log" "$DEPLOY/multi-hub-b.log" | awk -F: '{s+=$2} END{print s}')
curl -s -X POST "$A/internal/tool-gateway/invoke" -H 'Content-Type: application/json' -d "{\"toolId\":\"refund.create\",\"toolVersion\":\"2.1.0\",\"arguments\":{\"orderId\":\"10002\",\"reason\":\"DAMAGED\",\"amount\":\"0.01\"},\"executionContext\":{\"runId\":\"$RUNID\",\"toolCallId\":\"tc_replay\",\"sessionId\":\"$SESS\",\"idempotencyKey\":\"$RUNID-refund.create-3\"}}" > "$DEPLOY/multi_idem.json"
AFTER=$(grep -c 'toolId=refund.create .*status=succeeded' "$DEPLOY/multi-hub-a.log" "$DEPLOY/multi-hub-b.log" | awk -F: '{s+=$2} END{print s}')
check "cross-replica idempotency: no second execution" "$BEFORE" "$AFTER"
check "cross-replica idempotency: A audits replayed" 1 "$(grep -c 'toolId=refund.create .*status=replayed' "$DEPLOY/multi-hub-a.log")"

echo "--- 7. 记忆跨副本：A 写、B 读"
curl -s -N --max-time 8 -X POST "$A/agent/runs" "${HDR[@]}" -d '{"conversationId":"conv_mem","message":"看看我的订单",'"$CAP"'}' > "$DEPLOY/multi_mem1.log"
sleep 1
curl -s -N --max-time 8 -X POST "$B/agent/runs" "${HDR[@]}" -d '{"conversationId":"conv_mem","message":"第二个的物流",'"$CAP"'}' > "$DEPLOY/multi_mem2.log"
check "B resolves ordinal from memory written by A" order.logistics.get "$(data "$DEPLOY/multi_mem2.log" tool.selected | json "d['toolId']")"

echo "--- 8. 用户原话不进共享存储（Run 快照不含 message；与日志红线同一口径）"
# 首版 RunSnapshot 原样带了 Run.message，这条断言当场红了——运行时规划后没有任何路径再读它，落 Redis 纯属泄露面。
# 注意 ConversationMemory.lastTable.pendingMessage 会带一句挂起的原话（多轮「第二个」的功能需要），这里发的两句
# 都不走澄清路径，故用它们做探针；不把 pendingMessage 算作违规——那是设计内的、有 TTL 的短文本。
check "no user text in redis values" 0 "$(redis-cli -n ${REDIS_DB} --scan | while read -r k; do redis-cli -n ${REDIS_DB} GET "$k" 2>/dev/null; done | grep -cE '订单 10002 退款|看看我的订单')"

echo; echo "e2e-multi-instance: $pass passed, $fail failed"
[ "$fail" -eq 0 ]

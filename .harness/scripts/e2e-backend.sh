#!/usr/bin/env bash
# 端到端验收脚本（spec §6.2 第 5–15 条 + 阶段 4 评审补充的反例）。用法：bash .harness/scripts/e2e-backend.sh
# 前置：backed/app/target/app.jar 已构建；JDK 21 在 ~/.jenv/versions/21。
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DEPLOY="$ROOT/.harness/changes/feat-agent-tool-platform-20260903/deployment"
P="$ROOT/.harness/scripts/sse-parse.mjs"
JAVA="$HOME/.jenv/versions/21/bin/java"
BASE="http://localhost:8080"
HDR=(-H 'Content-Type: application/json' -H 'X-Tenant-Id: tenant_001' -H 'X-User-Id: user_001' -H 'X-Trace-Id: trace_e2e')
mkdir -p "$DEPLOY"
pass=0; fail=0
check() { # $1 name  $2 expected  $3 actual
  if [ "$2" = "$3" ]; then echo "  ✓ $1: $3"; pass=$((pass+1)); else echo "  ✗ $1: expected [$2] got [$3]"; fail=$((fail+1)); fi
}
json() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)"; }
events() { node "$P" "$1" --events; }
data() { node "$P" "$1" --data "$2"; }

pkill -f "app/target/app.jar" 2>/dev/null; sleep 1
owner=$(lsof -tnP -iTCP:8080 -sTCP:LISTEN 2>/dev/null | head -1)
if [ -n "${owner}" ]; then
  echo "port 8080 is held by PID ${owner}: $(ps -o command= -p "${owner}" | cut -c1-80)"
  echo "e2e-backend needs exclusive port 8080; stop that process and rerun."
  exit 2
fi
(JAVA_HOME="$HOME/.jenv/versions/21" "$JAVA" -jar "$ROOT/backed/app/target/app.jar" > "$DEPLOY/backend.log" 2>&1 &)
for i in $(seq 1 40); do sleep 1; grep -q "selfcheck: running" "$DEPLOY/backend.log" 2>/dev/null && break; grep -q "Application run failed" "$DEPLOY/backend.log" 2>/dev/null && break; done; sleep 2
if grep -q "Application run failed" "$DEPLOY/backend.log"; then echo "BOOT FAILED"; grep -m1 -A2 "Application run failed" "$DEPLOY/backend.log"; exit 1; fi
echo "boot: ready after ${i}s"

echo "--- selfchecks"
for s in "contracts 9 schemas, 20 examples OK" "refund.create idempotent OK" "plan 3 steps, step3 requiresConfirmation OK" "invalid toolId rejected OK" "token expired/replayed/digest-mismatch/extra-key rejected OK"; do
  check "selfcheck: $s" 1 "$(grep -v SelfCheckRunner "$DEPLOY/backend.log" | grep -c "selfcheck: $s")"
done

echo "--- §6.2.5 Registry search 按权限过滤"
srch() { curl -s -X POST "$BASE/internal/tool-registry/search" -H 'Content-Type: application/json' -d "{\"domain\":\"refund\",\"principal\":{\"userId\":\"$1\",\"tenantId\":\"tenant_001\"}}"; }
srch user_001 > "$DEPLOY/search_user_001.json"
check "user_001 tools" 4 "$(json "len(d['tools'])" < "$DEPLOY/search_user_001.json")"
check "user_002 tools" 3 "$(srch user_002 | json "len(d['tools'])")"
check "candidate has exactly 6 fields" 6 "$(json "len(d['tools'][0])" < "$DEPLOY/search_user_001.json")"
check "search response passes tool-search contract" OK "$(node -e "
const Ajv=require('$ROOT/.harness/node_modules/ajv/dist/2020.js').default;const fs=require('fs');const ajv=new Ajv({strict:true});require('$ROOT/.harness/node_modules/ajv-formats')(ajv);
const s=JSON.parse(fs.readFileSync('$ROOT/.harness/contracts/tool-search.schema.json'));ajv.addSchema(s);
const v=ajv.getSchema(s.\$id+'#/\$defs/response');const d=JSON.parse(fs.readFileSync('$DEPLOY/search_user_001.json'));console.log(v(d)?'OK':JSON.stringify(v.errors))")"

echo "--- §6.2.6 重复注册 → 409"
code=$(curl -s -o /tmp/dup.json -w '%{http_code}' -X POST "$BASE/internal/tool-registry/tools" -H 'Content-Type: application/json' --data @"$ROOT/.harness/contracts/examples/tool-manifest.refund-create.example.json")
check "http" 409 "$code"; check "code" TOOL_VERSION_CONFLICT "$(json "d['code']" < /tmp/dup.json)"

echo "--- §6.2.7 Gateway 缺参 → 400；无权限 → 403"
gwraw() { curl -s -o /tmp/gw.json -w '%{http_code}' -X POST "$BASE/internal/tool-gateway/invoke" -H 'Content-Type: application/json' -d "$1"; }
code=$(gwraw '{"toolId":"refund.eligibility.check","toolVersion":"1.2.0","arguments":{},"executionContext":{"runId":"run_probe","toolCallId":"tc_p1","userId":"user_001","tenantId":"tenant_001","idempotencyKey":"probe-p1"}}')
check "missing orderId http" 400 "$code"; check "code" REQUEST_INVALID "$(json "d['code']" < /tmp/gw.json)"
code=$(gwraw '{"toolId":"refund.create","toolVersion":"2.1.0","arguments":{"orderId":"10002","amount":"1.00","reason":"DAMAGED"},"executionContext":{"runId":"run_probe","toolCallId":"tc_p2","userId":"user_002","tenantId":"tenant_001","idempotencyKey":"probe-p2"}}')
check "user_002 refund.create http" 403 "$code"; check "code" FORBIDDEN "$(json "d['code']" < /tmp/gw.json)"

echo "--- 评审 M1：入站请求体按契约校验"
code=$(curl -s -o /tmp/m1.json -w '%{http_code}' -X POST "$BASE/agent/runs" "${HDR[@]}" -d '{"conversationId":"conv_m1","message":"退款","extra":1,"clientCapabilities":{"uiSchemaVersion":"1.0","components":["Card"]}}')
check "intent additionalProperties → 400" 400 "$code"; check "code" REQUEST_INVALID "$(json "d['code']" < /tmp/m1.json)"
code=$(curl -s -o /tmp/m1b.json -w '%{http_code}' -X POST "$BASE/agent/runs" "${HDR[@]}" -d '{"conversationId":"conv_m1","message":"退款","clientCapabilities":{"uiSchemaVersion":"2.0","components":["Card"]}}')
check "uiSchemaVersion const → 400" 400 "$code"
code=$(curl -s -o /tmp/m1c.json -w '%{http_code}' -X POST "$BASE/agent/runs/run_nope/actions/confirm-refund" "${HDR[@]}" -d '{"confirmationToken":"ct_xxxxxxxxxxxxxxxx","formData":{"reason":{"nested":1}}}')
check "formData nested object → 400 (before 404)" 400 "$code"
code=$(curl -s -o /tmp/m1d.json -w '%{http_code}' -X POST "$BASE/internal/tool-gateway/invoke" -H 'Content-Type: application/json' -d '{"toolId":"refund.status.get","toolVersion":"1.0.0","arguments":{"orderId":"10001"},"executionContext":{"runId":"bad id","toolCallId":"tc_x","userId":"u","tenantId":"t","idempotencyKey":"k"}}')
check "tool-invoke runId pattern → 400" 400 "$code"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/agent/runs" "${HDR[@]}" -d '{not json')
check "malformed JSON → 400" 400 "$code"

echo "--- §6.2.11 缺身份头 → 401"
code=$(curl -s -o /tmp/u.json -w '%{http_code}' -X POST "$BASE/agent/runs" -H 'Content-Type: application/json' --data @"$ROOT/.harness/contracts/examples/intent-request.example.json")
check "http" 401 "$code"; check "code" UNAUTHENTICATED "$(json "d['code']" < /tmp/u.json)"

echo "--- §6.2.8 POST /agent/runs"
curl -s -N --max-time 8 -X POST "$BASE/agent/runs" "${HDR[@]}" --data @"$ROOT/.harness/contracts/examples/intent-request.example.json" > "$DEPLOY/run_events.log"
check "event sequence" "run.started tool.selected tool.started tool.completed tool.selected tool.started tool.completed ui.replace confirmation.required" "$(events "$DEPLOY/run_events.log")"
RUNID=$(data "$DEPLOY/run_events.log" run.started | json "d['runId']")
TOKEN=$(data "$DEPLOY/run_events.log" ui.replace | json "[a for a in d['ui']['actions'] if a['id']=='confirm-refund'][0]['confirmationToken']")
check "ui.replace components" "['OrderCard', 'RefundConfirmCard', 'Form']" "$(data "$DEPLOY/run_events.log" ui.replace | json "[c['type'] for c in d['ui']['components']]")"
echo "  runId=$RUNID token=${TOKEN:0:14}…"

echo "--- §6.2.10a GET run-summary（等待确认中）"
curl -s "$BASE/agent/runs/$RUNID" -H 'X-Tenant-Id: tenant_001' -H 'X-User-Id: user_001' > "$DEPLOY/run_summary_waiting.json"
check "state" WAITING_CONFIRMATION "$(json "d['state']" < "$DEPLOY/run_summary_waiting.json")"

echo "--- §6.2.9 confirm"
curl -s -N --max-time 8 -X POST "$BASE/agent/runs/$RUNID/actions/confirm-refund" "${HDR[@]}" -d "{\"confirmationToken\":\"$TOKEN\",\"formData\":{\"reason\":\"DAMAGED\"}}" > "$DEPLOY/confirm_events.log"
check "event sequence" "tool.selected tool.started tool.completed tool.selected tool.started tool.completed ui.replace run.completed" "$(events "$DEPLOY/confirm_events.log")"
check "result components" "['ResultCard']" "$(data "$DEPLOY/confirm_events.log" ui.replace | json "[c['type'] for c in d['ui']['components']]")"

echo "--- §6.2.10b GET run-summary（完成后）"
curl -s "$BASE/agent/runs/$RUNID" -H 'X-Tenant-Id: tenant_001' -H 'X-User-Id: user_001' > "$DEPLOY/run_summary_done.json"
check "state" COMPLETED "$(json "d['state']" < "$DEPLOY/run_summary_done.json")"

echo "--- §6.2.12 令牌重放"
curl -s -N --max-time 5 -X POST "$BASE/agent/runs/$RUNID/actions/confirm-refund" "${HDR[@]}" -d "{\"confirmationToken\":\"$TOKEN\",\"formData\":{\"reason\":\"DAMAGED\"}}" > "$DEPLOY/replay_events.log"
check "events" "run.failed" "$(events "$DEPLOY/replay_events.log")"
check "code" CONFIRMATION_REJECTED "$(data "$DEPLOY/replay_events.log" run.failed | json "d['code']")"
check "refund.create succeeded audit lines" 1 "$(grep -c 'toolId=refund.create .*status=succeeded' "$DEPLOY/backend.log")"

echo "--- §6.2.12b formData 注入白名单外键（amount）"
curl -s -N --max-time 8 -X POST "$BASE/agent/runs" "${HDR[@]}" -d '{"conversationId":"conv_002","message":"这个订单退款","pageContext":{"page":"order-detail","selectedEntity":{"type":"order","id":"10002"}},"clientCapabilities":{"uiSchemaVersion":"1.0","components":["Form","Card","Table","ResultCard","ConfirmationCard","OrderCard","RefundConfirmCard"]}}' > "$DEPLOY/run2_events.log"
R2=$(data "$DEPLOY/run2_events.log" run.started | json "d['runId']"); T2=$(data "$DEPLOY/run2_events.log" ui.replace | json "[a for a in d['ui']['actions'] if a['id']=='confirm-refund'][0]['confirmationToken']")
curl -s -N --max-time 5 -X POST "$BASE/agent/runs/$R2/actions/confirm-refund" "${HDR[@]}" -d "{\"confirmationToken\":\"$T2\",\"formData\":{\"reason\":\"DAMAGED\",\"amount\":\"0.01\"}}" > "$DEPLOY/inject_events.log"
check "code" CONFIRMATION_REJECTED "$(data "$DEPLOY/inject_events.log" run.failed | json "d['code']")"
gw() { curl -s -X POST "$BASE/internal/tool-gateway/invoke" -H 'Content-Type: application/json' -d "{\"toolId\":\"refund.status.get\",\"toolVersion\":\"1.0.0\",\"arguments\":{\"orderId\":\"$1\"},\"executionContext\":{\"runId\":\"run_probe\",\"toolCallId\":\"tc_$1\",\"userId\":\"user_001\",\"tenantId\":\"tenant_001\",\"idempotencyKey\":\"probe-$1\"}}" | json "len(d['output']['refunds'])"; }
check "refunds for 10002 (injection must not create)" 0 "$(gw 10002)"

echo "--- §6.2.13 refunds for 10001"
check "refunds.length" 1 "$(gw 10001)"

echo "--- 评审 M2：并发两次确认，Run 仍 COMPLETED 且只有 1 笔退款（订单 10002）"
curl -s -N --max-time 8 -X POST "$BASE/agent/runs" "${HDR[@]}" -d '{"conversationId":"conv_m2","message":"这个订单退款","pageContext":{"page":"order-detail","selectedEntity":{"type":"order","id":"10002"}},"clientCapabilities":{"uiSchemaVersion":"1.0","components":["Form","Card","Table","ResultCard","ConfirmationCard","OrderCard","RefundConfirmCard"]}}' > "$DEPLOY/run3_events.log"
R3=$(data "$DEPLOY/run3_events.log" run.started | json "d['runId']"); T3=$(data "$DEPLOY/run3_events.log" ui.replace | json "[a for a in d['ui']['actions'] if a['id']=='confirm-refund'][0]['confirmationToken']")
BODY3="{\"confirmationToken\":\"$T3\",\"formData\":{\"reason\":\"DAMAGED\"}}"
curl -s -N --max-time 8 -X POST "$BASE/agent/runs/$R3/actions/confirm-refund" "${HDR[@]}" -d "$BODY3" > "$DEPLOY/m2_a.log" &
curl -s -N --max-time 8 -X POST "$BASE/agent/runs/$R3/actions/confirm-refund" "${HDR[@]}" -d "$BODY3" > "$DEPLOY/m2_b.log" &
wait
OUT_A=$(events "$DEPLOY/m2_a.log"); OUT_B=$(events "$DEPLOY/m2_b.log")
check "exactly one stream completed" 1 "$(printf '%s\n%s\n' "$OUT_A" "$OUT_B" | grep -c 'run.completed')"
check "the other stream rejected" 1 "$(printf '%s\n%s\n' "$OUT_A" "$OUT_B" | grep -c '^run.failed$')"
check "run-summary state" COMPLETED "$(curl -s "$BASE/agent/runs/$R3" -H 'X-Tenant-Id: tenant_001' -H 'X-User-Id: user_001' | json "d['state']")"
check "refunds for 10002" 1 "$(gw 10002)"

echo "--- 评审 M3：执行金额 == 确认屏展示金额"
SHOWN=$(data "$DEPLOY/run_events.log" ui.replace | json "[c for c in d['ui']['components'] if c['type']=='RefundConfirmCard'][0]['props']['amount']")
EXECUTED=$(data "$DEPLOY/confirm_events.log" ui.replace | json "[x for x in d['ui']['components'][0]['props']['details'] if x['label']=='退款金额'][0]['value']")
check "shown amount" 128.00 "$SHOWN"; check "executed amount non-empty" 1 "$([ -n "$EXECUTED" ] && echo 1 || echo 0)"; check "executed amount equals shown" "$SHOWN" "$EXECUTED"

echo "--- 无能力路径"
curl -s -N --max-time 5 -X POST "$BASE/agent/runs" "${HDR[@]}" -d '{"conversationId":"conv_003","message":"今天天气怎么样","clientCapabilities":{"uiSchemaVersion":"1.0","components":["Card"]}}' > "$DEPLOY/nocap_events.log"
check "events" "run.started message.delta run.completed" "$(events "$DEPLOY/nocap_events.log")"

echo "--- 其他"
check "unknown runId → 404" 404 "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/agent/runs/run_nope/actions/x" "${HDR[@]}" -d '{"confirmationToken":"ct_xxxxxxxxxxxxxxxx","formData":{}}')"
check "§6.2.14 audit fields" 9 "$(grep -m1 'audit runId=' "$DEPLOY/backend.log" | grep -o '[a-zA-Z]*=' | wc -l | tr -d ' ')"
check "§6.2.15 user text in log" 0 "$(grep -c '帮我把这个订单退款' "$DEPLOY/backend.log")"
check "ERROR lines" 0 "$(grep -c ' ERROR ' "$DEPLOY/backend.log")"

pkill -f "app/target/app.jar"
echo; echo "e2e-backend: $pass passed, $fail failed"
[ "$fail" -eq 0 ]

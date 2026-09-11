#!/usr/bin/env bash
# 端到端验收脚本（spec §6.2 第 5–15 条 + 阶段 4 评审补充的反例）。用法：bash .harness/scripts/e2e-backend.sh
# 前置：spark-rooter/examples/host-demo/target/host-demo.jar 已构建（根 ./mvnw install 后在 examples/host-demo mvn -o package）；JDK 21 在 ~/.jenv/versions/21。
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$ROOT/.harness/scripts/lib/change-dir.sh"
P="$ROOT/.harness/scripts/sse-parse.mjs"
JAVA="$HOME/.jenv/versions/21/bin/java"
# 端口可用 SPARK_PORT 覆盖（默认 8080；本机另有实例时用 8091 等，脚本会自己起一个）
PORT="${SPARK_PORT:-8080}"
BASE="http://localhost:$PORT"
# 内核不识别身份：无身份头（change 5）；X-Trace-Id 可选
HDR=(-H 'Content-Type: application/json' -H 'X-Trace-Id: trace_e2e')
pass=0; fail=0
# 规则规划器毫秒级；接真实模型时规划 5–15s，SSE 读取超时随之放大
# 真模型单次规划实测 5–70s（含网关抖动重试），SSE 读取超时给到 90s
if [ -n "${SPARK_LLM_API_KEY:-}" ]; then SSE_T=90; LIVE_LLM=1; else SSE_T=8; LIVE_LLM=0; fi
check() { # $1 name  $2 expected  $3 actual
  if [ "$2" = "$3" ]; then echo "  ✓ $1: $3"; pass=$((pass+1)); else echo "  ✗ $1: expected [$2] got [$3]"; fail=$((fail+1)); fi
}
json() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)"; }
events() { node "$P" "$1" --events; }
data() { node "$P" "$1" --data "$2"; }

# 只清理本脚本自己起的实例（带 --server.port=$PORT），不碰 IDE 里手动启动的
pkill -f "examples/host-demo/target/host-demo.jar --server.port=$PORT" 2>/dev/null; sleep 1
owner=$(lsof -tnP -iTCP:"$PORT" -sTCP:LISTEN 2>/dev/null | head -1)
if [ -n "${owner}" ]; then
  echo "port $PORT is held by PID ${owner}: $(ps -o command= -p "${owner}" | cut -c1-80)"
  echo "e2e-backend needs exclusive port $PORT; stop that process or set SPARK_PORT, and rerun."
  exit 2
fi
(JAVA_HOME="$HOME/.jenv/versions/21" "$JAVA" -jar "$ROOT/spark-rooter/examples/host-demo/target/host-demo.jar" --server.port="$PORT" --spring.profiles.active=e2e > "$DEPLOY/backend.log" 2>&1 &)
for i in $(seq 1 40); do sleep 1; grep -q "selfcheck: running" "$DEPLOY/backend.log" 2>/dev/null && break; grep -q "Application run failed" "$DEPLOY/backend.log" 2>/dev/null && break; done; sleep 2
if grep -q "Application run failed" "$DEPLOY/backend.log"; then echo "BOOT FAILED"; grep -m1 -A2 "Application run failed" "$DEPLOY/backend.log"; exit 1; fi
echo "boot: ready after ${i}s"

check "tools registered (12 domain + demo.whoami + selfcheck echo)" 1 "$(grep -c "spark-rooter: 14 tools registered from 6 beans" "$DEPLOY/backend.log")"
echo "--- selfchecks"
PLAN_CHECK="plan 12 messages OK"; [ "$LIVE_LLM" = 1 ] && PLAN_CHECK="plan skipped (live LLM"
for s in "contracts 9 schemas, 27 examples OK" "refund.create idempotent OK" "$PLAN_CHECK" "invalid toolId rejected OK" "missing prerequisite rejected OK" "foreign entity arg rejected OK" "schema-violating arg rejected OK" "intent verbs reference registered tools OK" "token expired/replayed/digest-mismatch/extra-key/session-mismatch rejected OK" "gateway idempotency claim OK" "confirmation coverage OK" "inline actions OK" "proxy invocation OK (aspect fired once)" "manifest parity"; do
  check "selfcheck: $s" 1 "$(grep -v SelfCheckRunner "$DEPLOY/backend.log" | grep -c "selfcheck: $s")"
done

echo "--- §6.2.5 Registry search（无身份；按 status 过滤，权限归宿主）"
srch() { curl -s -X POST "$BASE/internal/tool-registry/search" -H 'Content-Type: application/json' -d "{\"domain\":\"$1\"}"; }
srch refund > "$DEPLOY/search_refund.json"
check "refund tools" 4 "$(json "len(d['tools'])" < "$DEPLOY/search_refund.json")"
check "candidate has exactly 6 fields" 6 "$(json "len(d['tools'][0])" < "$DEPLOY/search_refund.json")"
code=$(curl -s -o /tmp/sp.json -w '%{http_code}' -X POST "$BASE/internal/tool-registry/search" -H 'Content-Type: application/json' -d '{"domain":"refund","principal":{"sessionId":"e2e"}}')
check "search with principal → 400 (contract additionalProperties)" 400 "$code"
check "search response passes tool-search contract" OK "$(node -e "
const Ajv=require('$ROOT/.harness/node_modules/ajv/dist/2020.js').default;const fs=require('fs');const ajv=new Ajv({strict:true});require('$ROOT/.harness/node_modules/ajv-formats')(ajv);
const s=JSON.parse(fs.readFileSync('$ROOT/.harness/contracts/tool-search.schema.json'));ajv.addSchema(s);
const v=ajv.getSchema(s.\$id+'#/\$defs/response');const d=JSON.parse(fs.readFileSync('$DEPLOY/search_refund.json'));console.log(v(d)?'OK':JSON.stringify(v.errors))")"

echo "--- §6.2.6 重复注册 → 409"
code=$(curl -s -o /tmp/dup.json -w '%{http_code}' -X POST "$BASE/internal/tool-registry/tools" -H 'Content-Type: application/json' --data @"$ROOT/.harness/contracts/examples/tool-manifest.refund-create.example.json")
check "http" 409 "$code"; check "code" TOOL_VERSION_CONFLICT "$(json "d['code']" < /tmp/dup.json)"

echo "--- §6.2.7 Gateway 缺参 → 400"
gwraw() { curl -s -o /tmp/gw.json -w '%{http_code}' -X POST "$BASE/internal/tool-gateway/invoke" -H 'Content-Type: application/json' -d "$1"; }
code=$(gwraw '{"toolId":"refund.eligibility.check","toolVersion":"1.3.0","arguments":{},"executionContext":{"runId":"run_probe","toolCallId":"tc_p1","sessionId":"e2e","idempotencyKey":"probe-p1"}}')
check "missing orderId http" 400 "$code"; check "code" REQUEST_INVALID "$(json "d['code']" < /tmp/gw.json)"

echo "--- 评审 M1：入站请求体按契约校验"
code=$(curl -s -o /tmp/m1.json -w '%{http_code}' -X POST "$BASE/agent/runs" "${HDR[@]}" -d '{"conversationId":"conv_m1","message":"退款","extra":1,"clientCapabilities":{"uiSchemaVersion":"1.0","components":["Card"]}}')
check "intent additionalProperties → 400" 400 "$code"; check "code" REQUEST_INVALID "$(json "d['code']" < /tmp/m1.json)"
code=$(curl -s -o /tmp/m1b.json -w '%{http_code}' -X POST "$BASE/agent/runs" "${HDR[@]}" -d '{"conversationId":"conv_m1","message":"退款","clientCapabilities":{"uiSchemaVersion":"2.0","components":["Card"]}}')
check "uiSchemaVersion const → 400" 400 "$code"
code=$(curl -s -o /tmp/m1c.json -w '%{http_code}' -X POST "$BASE/agent/runs/run_nope/actions/confirm-refund" "${HDR[@]}" -d '{"confirmationToken":"ct_xxxxxxxxxxxxxxxx","formData":{"reason":{"nested":1}}}')
check "formData nested object → 400 (before 404)" 400 "$code"
code=$(curl -s -o /tmp/m1d.json -w '%{http_code}' -X POST "$BASE/internal/tool-gateway/invoke" -H 'Content-Type: application/json' -d '{"toolId":"refund.status.get","toolVersion":"1.0.0","arguments":{"orderId":"10001"},"executionContext":{"runId":"bad id","toolCallId":"tc_x","sessionId":"e2e","idempotencyKey":"k"}}')
check "tool-invoke runId pattern → 400" 400 "$code"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/agent/runs" "${HDR[@]}" -d '{not json')
check "malformed JSON → 400" 400 "$code"

echo "--- §6.2.11 请求体带 pageContext → 400（前端只发自然语言）"
code=$(curl -s -o /tmp/u.json -w '%{http_code}' -X POST "$BASE/agent/runs" "${HDR[@]}" -d '{"conversationId":"conv_pc","message":"退款","pageContext":{"page":"x"},"clientCapabilities":{"uiSchemaVersion":"1.0","components":["Card"]}}')
check "http" 400 "$code"; check "code" REQUEST_INVALID "$(json "d['code']" < /tmp/u.json)"

echo "--- §6.2.8 POST /agent/runs"
curl -s -N --max-time "$SSE_T" -X POST "$BASE/agent/runs" "${HDR[@]}" --data @"$ROOT/.harness/contracts/examples/intent-request.example.json" > "$DEPLOY/run_events.log"
check "event sequence" "run.started tool.selected tool.started tool.completed tool.selected tool.started tool.completed ui.replace confirmation.required" "$(events "$DEPLOY/run_events.log")"
RUNID=$(data "$DEPLOY/run_events.log" run.started | json "d['runId']")
TOKEN=$(data "$DEPLOY/run_events.log" ui.replace | json "[a for a in d['ui']['actions'] if a['id']=='confirm-refund'][0]['confirmationToken']")
check "ui.replace components" "['Card', 'Card', 'Form']" "$(data "$DEPLOY/run_events.log" ui.replace | json "[c['type'] for c in d['ui']['components']]")"
echo "  runId=$RUNID token=${TOKEN:0:14}…"

echo "--- §6.2.10a GET run-summary（等待确认中）"
curl -s "$BASE/agent/runs/$RUNID" > "$DEPLOY/run_summary_waiting.json"
check "state" WAITING_CONFIRMATION "$(json "d['state']" < "$DEPLOY/run_summary_waiting.json")"

echo "--- §6.2.9 confirm"
curl -s -N --max-time "$SSE_T" -X POST "$BASE/agent/runs/$RUNID/actions/confirm-refund" "${HDR[@]}" -d "{\"confirmationToken\":\"$TOKEN\",\"formData\":{\"reason\":\"DAMAGED\"}}" > "$DEPLOY/confirm_events.log"
check "event sequence" "tool.selected tool.started tool.completed tool.selected tool.started tool.completed ui.replace run.completed" "$(events "$DEPLOY/confirm_events.log")"
check "result components" "['Result']" "$(data "$DEPLOY/confirm_events.log" ui.replace | json "[c['type'] for c in d['ui']['components']]")"

echo "--- §6.2.10b GET run-summary（完成后）"
curl -s "$BASE/agent/runs/$RUNID" > "$DEPLOY/run_summary_done.json"
check "state" COMPLETED "$(json "d['state']" < "$DEPLOY/run_summary_done.json")"

echo "--- §6.2.12 令牌重放"
curl -s -N --max-time "$SSE_T" -X POST "$BASE/agent/runs/$RUNID/actions/confirm-refund" "${HDR[@]}" -d "{\"confirmationToken\":\"$TOKEN\",\"formData\":{\"reason\":\"DAMAGED\"}}" > "$DEPLOY/replay_events.log"
check "events" "run.failed" "$(events "$DEPLOY/replay_events.log")"
check "code" CONFIRMATION_REJECTED "$(data "$DEPLOY/replay_events.log" run.failed | json "d['code']")"
check "refund.create succeeded audit lines" 1 "$(grep -c 'toolId=refund.create .*status=succeeded' "$DEPLOY/backend.log")"

echo "--- §6.2.12b formData 注入白名单外键（amount）"
curl -s -N --max-time "$SSE_T" -X POST "$BASE/agent/runs" "${HDR[@]}" -d '{"conversationId":"conv_002","message":"订单 10002 退款","clientCapabilities":{"uiSchemaVersion":"1.0","components":["Form","Card","Table","Result","Timeline"]}}' > "$DEPLOY/run2_events.log"
R2=$(data "$DEPLOY/run2_events.log" run.started | json "d['runId']"); T2=$(data "$DEPLOY/run2_events.log" ui.replace | json "[a for a in d['ui']['actions'] if a['id']=='confirm-refund'][0]['confirmationToken']")
curl -s -N --max-time "$SSE_T" -X POST "$BASE/agent/runs/$R2/actions/confirm-refund" "${HDR[@]}" -d "{\"confirmationToken\":\"$T2\",\"formData\":{\"reason\":\"DAMAGED\",\"amount\":\"0.01\"}}" > "$DEPLOY/inject_events.log"
check "code" CONFIRMATION_REJECTED "$(data "$DEPLOY/inject_events.log" run.failed | json "d['code']")"
# 评审 M-1：确认屏订单 Card 必须显示真实状态（10002 种子为 SHIPPED），不得出现猜测的默认值
check "confirm screen shows real order status" SHIPPED "$(data "$DEPLOY/run2_events.log" ui.replace | json "[i for i in [c for c in d['ui']['components'] if c['id']=='order'][0]['props']['items'] if i['label']=='状态'][0]['value']")"
gw() { curl -s -X POST "$BASE/internal/tool-gateway/invoke" -H 'Content-Type: application/json' -d "{\"toolId\":\"refund.status.get\",\"toolVersion\":\"1.0.0\",\"arguments\":{\"orderId\":\"$1\"},\"executionContext\":{\"runId\":\"run_probe\",\"toolCallId\":\"tc_$1\",\"sessionId\":\"e2e\",\"idempotencyKey\":\"probe-$1\"}}" | json "len(d['output']['refunds'])"; }
check "refunds for 10002 (injection must not create)" 0 "$(gw 10002)"

echo "--- §6.2.13 refunds for 10001"
check "refunds.length" 1 "$(gw 10001)"

echo "--- 评审 M2：并发两次确认，Run 仍 COMPLETED 且只有 1 笔退款（订单 10002）"
curl -s -N --max-time "$SSE_T" -X POST "$BASE/agent/runs" "${HDR[@]}" -d '{"conversationId":"conv_m2","message":"订单 10002 退款","clientCapabilities":{"uiSchemaVersion":"1.0","components":["Form","Card","Table","Result","Timeline"]}}' > "$DEPLOY/run3_events.log"
R3=$(data "$DEPLOY/run3_events.log" run.started | json "d['runId']"); T3=$(data "$DEPLOY/run3_events.log" ui.replace | json "[a for a in d['ui']['actions'] if a['id']=='confirm-refund'][0]['confirmationToken']")
BODY3="{\"confirmationToken\":\"$T3\",\"formData\":{\"reason\":\"DAMAGED\"}}"
curl -s -N --max-time "$SSE_T" -X POST "$BASE/agent/runs/$R3/actions/confirm-refund" "${HDR[@]}" -d "$BODY3" > "$DEPLOY/m2_a.log" &
curl -s -N --max-time "$SSE_T" -X POST "$BASE/agent/runs/$R3/actions/confirm-refund" "${HDR[@]}" -d "$BODY3" > "$DEPLOY/m2_b.log" &
wait
OUT_A=$(events "$DEPLOY/m2_a.log"); OUT_B=$(events "$DEPLOY/m2_b.log")
check "exactly one stream completed" 1 "$(printf '%s\n%s\n' "$OUT_A" "$OUT_B" | grep -c 'run.completed')"
check "the other stream rejected" 1 "$(printf '%s\n%s\n' "$OUT_A" "$OUT_B" | grep -c '^run.failed$')"
check "run-summary state" COMPLETED "$(curl -s "$BASE/agent/runs/$R3" | json "d['state']")"
check "refunds for 10002" 1 "$(gw 10002)"

echo "--- 评审 M3：执行金额 == 确认屏展示金额"
SHOWN=$(data "$DEPLOY/run_events.log" ui.replace | json "[i for i in [c for c in d['ui']['components'] if c['id']=='refund-summary'][0]['props']['items'] if i['label']=='退款金额'][0]['value']")
EXECUTED=$(data "$DEPLOY/confirm_events.log" ui.replace | json "[x for x in d['ui']['components'][0]['props']['details'] if x['label']=='退款金额'][0]['value']")
check "shown amount" 128.00 "$SHOWN"; check "executed amount non-empty" 1 "$([ -n "$EXECUTED" ] && echo 1 || echo 0)"; check "executed amount equals shown" "$SHOWN" "$EXECUTED"

echo "--- 意图路由 ①：缺实体拦截（无 pageContext 说「退钱」）"
curl -s -N --max-time "$SSE_T" -X POST "$BASE/agent/runs" "${HDR[@]}" -d '{"conversationId":"conv_r1","message":"退钱","clientCapabilities":{"uiSchemaVersion":"1.0","components":["Card"]}}' > "$DEPLOY/route1_events.log"
# change 5：缺实体不再只提示，而是拉订单列表出澄清屏（spec §4.5）；仍不进规划、不调目标工具
check "① events" "run.started tool.selected tool.started tool.completed ui.replace message.delta run.completed" "$(events "$DEPLOY/route1_events.log")"
R1=$(data "$DEPLOY/route1_events.log" run.started | json "d['runId']")
T1=$(data "$DEPLOY/route1_events.log" message.delta | json "d['text']")
check "① clarify tool is the order list" order.list.search "$(data "$DEPLOY/route1_events.log" tool.selected | json "d['toolId']")"
check "① text asks to pick" 1 "$(echo "$T1" | grep -c '请选择')"
check "① text does not echo user message" 0 "$(echo "$T1" | grep -c '退钱')"
check "① no refund tool audit for this run (only the clarify list)" 0 "$(grep "audit runId=$R1" "$DEPLOY/backend.log" | grep -vc "toolId=order.list.search")"
curl -s "$BASE/agent/runs/$R1" > "$DEPLOY/route1_summary.json"
check "① state COMPLETED without failureCode" "COMPLETED-none" "$(json "d['state']+'-'+str(d.get('failureCode','none'))" < "$DEPLOY/route1_summary.json")"

echo "--- 意图路由 ②：闲聊 → 无能力路径（规则不误路由）"
curl -s -N --max-time "$SSE_T" -X POST "$BASE/agent/runs" "${HDR[@]}" -d '{"conversationId":"conv_r2","message":"今天天气怎么样","clientCapabilities":{"uiSchemaVersion":"1.0","components":["Card"]}}' > "$DEPLOY/route2_events.log"
check "② events" "run.started message.delta run.completed" "$(events "$DEPLOY/route2_events.log")"

echo "--- 意图路由 ③：模型补位（仅 LIVE）"
if [ "$LIVE_LLM" = 1 ]; then
  curl -s -N --max-time "$SSE_T" -X POST "$BASE/agent/runs" "${HDR[@]}" -d '{"conversationId":"conv_r3","message":"我想把订单 10002 的钱要回来","clientCapabilities":{"uiSchemaVersion":"1.0","components":["Form","Card","Table","Result","Timeline"]}}' > "$DEPLOY/route3_events.log"
  R3=$(data "$DEPLOY/route3_events.log" run.started | json "d['runId']")
  check "③a route by model" 1 "$(grep -c "route runId=$R3 domain=refund source=model" "$DEPLOY/backend.log")"
  check "③b event sequence" "run.started tool.selected tool.started tool.completed tool.selected tool.started tool.completed ui.replace confirmation.required" "$(events "$DEPLOY/route3_events.log")"
else
  echo "  - ③ skipped (rule mode)"
fi

echo "--- 幂等 ④：同 idempotencyKey 两次 refund.create（订单 10004）"
idem() { curl -s -X POST "$BASE/internal/tool-gateway/invoke" -H 'Content-Type: application/json' -d "{\"toolId\":\"refund.create\",\"toolVersion\":\"2.1.0\",\"arguments\":{\"orderId\":\"10004\",\"amount\":\"59.00\",\"reason\":\"DAMAGED\"},\"executionContext\":{\"runId\":\"run_e2eidem\",\"toolCallId\":\"$1\",\"sessionId\":\"e2e\",\"idempotencyKey\":\"e2e-idem-10004\"}}"; }
ID1=$(idem tc_idem1 | json "d['output']['refundId']"); ID2=$(idem tc_idem2 | json "d['output']['refundId']")
check "④ same refundId" "$ID1" "$ID2"
check "④ refundId non-empty" 1 "$([ -n "$ID1" ] && echo 1 || echo 0)"
check "④ succeeded audit exactly once" 1 "$(grep -c 'runId=run_e2eidem .*status=succeeded' "$DEPLOY/backend.log")"
check "④ replayed audit exactly once" 1 "$(grep -c 'runId=run_e2eidem .*status=replayed' "$DEPLOY/backend.log")"
check "④ refunds for 10004" 1 "$(gw 10004)"

CAP='"clientCapabilities":{"uiSchemaVersion":"1.0","components":["Form","Card","Table","Result","Timeline"]}'
run_msg() { curl -s -N --max-time "$SSE_T" -X POST "$BASE/agent/runs" "${HDR[@]}" -d "{\"conversationId\":\"$1\",\"message\":\"$2\",$CAP}" > "$DEPLOY/$1.log"; }
types() { data "$DEPLOY/$1.log" ui.replace | json "[c['type'] for c in d['ui']['components']]"; }
comp() { data "$DEPLOY/$1.log" ui.replace | json "[c for c in d['ui']['components'] if c['id']=='$2'][0]['props']$3"; }
submit_id() { data "$DEPLOY/$1.log" ui.replace | json "[a for a in d['ui']['actions'] if a['type']=='submit'][0]['id']"; }
token_of() { data "$DEPLOY/$1.log" ui.replace | json "[a for a in d['ui']['actions'] if a['type']=='submit'][0]['confirmationToken']"; }
runid_of() { data "$DEPLOY/$1.log" run.started | json "d['runId']"; }
confirm_run() { # $1 case  $2 formData json  $3 out-name
  curl -s -N --max-time "$SSE_T" -X POST "$BASE/agent/runs/$(runid_of "$1")/actions/$(submit_id "$1")" "${HDR[@]}" -d "{\"confirmationToken\":\"$(token_of "$1")\",\"formData\":$2}" > "$DEPLOY/$3.log"; }
gwc() { # $1 toolId $2 version $3 args $4 userId
  curl -s -o /tmp/gwc.json -w '%{http_code}' -X POST "$BASE/internal/tool-gateway/invoke" -H 'Content-Type: application/json' -d "{\"toolId\":\"$1\",\"toolVersion\":\"$2\",\"arguments\":$3,\"executionContext\":{\"runId\":\"run_e2e_gw\",\"toolCallId\":\"tc_$RANDOM\",\"sessionId\":\"e2e\",\"idempotencyKey\":\"e2e-$RANDOM$RANDOM\"}}"; }

echo "--- ⑦ 看看我的订单 → Table 20 / 30"
run_msg c7 "看看我的订单"
check "⑦ events" "run.started tool.selected tool.started tool.completed ui.replace run.completed" "$(events "$DEPLOY/c7.log")"
check "⑦ types" "['Table']" "$(types c7)"
T7() { data "$DEPLOY/c7.log" ui.replace | json "$1"; }
check "⑦ rows/total/first" "20/30/10030" "$(T7 "(lambda p: f\"{len(p['rows'])}/{p['total']}/{p['rows'][0]['id']}\")([c for c in d['ui']['components'] if c['id']=='orders'][0]['props'])")"
check "⑦ row 10029 has 删除订单 intent" 1 "$(T7 "sum(1 for x in [c for c in d['ui']['components'] if c['id']=='orders'][0]['props']['rows'][1]['actions'] if x['label']=='删除订单' and '10029' in x['intent'])")"

echo "--- ⑧ 查看订单 10002 的物流 → Card + Timeline"
run_msg c8 "查看订单 10002 的物流"
check "⑧ tool" order.logistics.get "$(data "$DEPLOY/c8.log" tool.selected | json "d['toolId']")"
check "⑧ types" "['Card', 'Timeline']" "$(types c8)"
check "⑧ timeline ≥ 3" 1 "$(data "$DEPLOY/c8.log" ui.replace | json "1 if len([c for c in d['ui']['components'] if c['id']=='logistics-events'][0]['props']['items'])>=3 else 0")"
check "⑧ card has 运单号" 1 "$(data "$DEPLOY/c8.log" ui.replace | json "sum(1 for i in [c for c in d['ui']['components'] if c['id']=='logistics'][0]['props']['items'] if i['label']=='运单号')")"

echo "--- ⑧' 10030查看物流（无「订单」前缀）→ order.logistics.get，Card 标题含 10030"
run_msg c8b "10030查看物流"
check "⑧' tool" order.logistics.get "$(data "$DEPLOY/c8b.log" tool.selected | json "d['toolId']")"
check "⑧' card title has 10030" 1 "$(data "$DEPLOY/c8b.log" ui.replace | json "1 if '10030' in [c for c in d['ui']['components'] if c['id']=='logistics'][0]['props']['title'] else 0")"

echo "--- ⑨ 有什么商品 → Table 20 / 20"
run_msg c9 "有什么商品"
check "⑨ tool" product.list.search "$(data "$DEPLOY/c9.log" tool.selected | json "d['toolId']")"
check "⑨ rows/total" "20/20" "$(data "$DEPLOY/c9.log" ui.replace | json "(lambda p: f\"{len(p['rows'])}/{p['total']}\")([c for c in d['ui']['components'] if c['id']=='products'][0]['props'])")"

echo "--- ⑩ 查看商品 P-1003 的详情 → Card"
run_msg c10 "查看商品 P-1003 的详情"
check "⑩ types" "['Card']" "$(types c10)"
check "⑩ title" "无线耳机 Pro" "$(comp c10 product "['title']")"

echo "--- ⑪ 订单 10002 申请售后 → [Card, Form] → 确认 → Result"
run_msg c11 "订单 10002 申请售后"
check "⑪ events" "run.started tool.selected tool.started tool.completed ui.replace confirmation.required" "$(events "$DEPLOY/c11.log")"
check "⑪ tool" aftersale.list.get "$(data "$DEPLOY/c11.log" tool.selected | json "d['toolId']")"
check "⑪ types" "['Card', 'Form']" "$(types c11)"
check "⑪ submit id" confirm-aftersale "$(submit_id c11)"
confirm_run c11 '{"type":"RETURN","reason":"包装破损"}' c11b
check "⑪ confirm events" "tool.selected tool.started tool.completed tool.selected tool.started tool.completed ui.replace run.completed" "$(events "$DEPLOY/c11b.log")"
check "⑪ result types" "['Result']" "$(types c11b)"
gwc aftersale.list.get 1.0.0 '{"orderId":"10002"}' >/dev/null; check "⑪ aftersale.list.get 10002" 1 "$(json "len(d['output']['items'])" < /tmp/gwc.json)"

echo "--- ⑫ 删除订单 10005 → [Card] danger 无 Form → 确认 {} → Result → total 29"
run_msg c12 "删除订单 10005"
check "⑫ types" "['Card']" "$(types c12)"
check "⑫ last item tone" danger "$(comp c12 order "['items'][-1]['tone']")"
check "⑫ submit id" confirm-delete "$(submit_id c12)"
confirm_run c12 '{}' c12b
check "⑫ result types" "['Result']" "$(types c12b)"
gwc order.list.search 1.1.0 '{}' >/dev/null; check "⑫ total after delete" 29 "$(json "d['output']['total']" < /tmp/gwc.json)"

echo "--- ⑬ 删除订单 10001（PAID）→ 确认 → CONFIRMATION_REJECTED，order.delete 审计 0"
run_msg c13 "删除订单 10001"
check "⑬ confirmation shown" 1 "$(events "$DEPLOY/c13.log" | grep -c 'confirmation.required$')"
confirm_run c13 '{}' c13b
check "⑬ code" CONFIRMATION_REJECTED "$(data "$DEPLOY/c13b.log" run.failed | json "d['code']")"
check "⑬ message" "对象状态已变化，本次操作未执行" "$(data "$DEPLOY/c13b.log" run.failed | json "d['message']")"
check "⑬ order.delete audit in run" 0 "$(grep -c "audit runId=$(runid_of c13) .*toolId=order.delete" "$DEPLOY/backend.log")"
echo "--- ⑬' Gateway 直调 order.delete 10001 → 502 HANDLER_ERROR"
code=$(gwc order.delete 1.0.0 '{"orderId":"10001"}'); check "⑬' http" 502 "$code"; check "⑬' code" INTERNAL_ERROR "$(json "d['code']" < /tmp/gwc.json)"
check "⑬' audit failed:HANDLER_ERROR" 1 "$([ "$(grep -c 'runId=run_e2e_gw .*toolId=order.delete .*status=failed' "$DEPLOY/backend.log")" -ge 1 ] && echo 1 || echo 0)"
gwc order.list.search 1.1.0 '{"status":"PAID"}' >/dev/null; check "⑬ 10001 still listed" 1 "$(json "sum(1 for i in d['output']['items'] if i['orderId']=='10001')" < /tmp/gwc.json)"

echo "--- ⑭ 宿主方法级切面：X-Demo-User: guest 删除订单 10010 → 确认后 → 切面拒 → TOOL_EXECUTION_FAILED"
GUEST=(-H 'Content-Type: application/json' -H 'X-Demo-User: guest')
curl -s -N --max-time "$SSE_T" -X POST "$BASE/agent/runs" "${GUEST[@]}" -d "{\"conversationId\":\"c14\",\"message\":\"删除订单 10010\",$CAP}" > "$DEPLOY/c14.log"
check "⑭ events" "run.started tool.selected tool.started tool.completed ui.replace confirmation.required" "$(events "$DEPLOY/c14.log")"
curl -s -N --max-time "$SSE_T" -X POST "$BASE/agent/runs/$(runid_of c14)/actions/$(submit_id c14)" "${GUEST[@]}" -d "{\"confirmationToken\":\"$(token_of c14)\",\"formData\":{}}" > "$DEPLOY/c14b.log"
check "⑭ confirm code" TOOL_EXECUTION_FAILED "$(data "$DEPLOY/c14b.log" run.failed | json "d['code']")"
check "⑭ aspect denied logged" 1 "$([ "$(grep -c 'denied user=guest .*OrderTools.delete' "$DEPLOY/backend.log")" -ge 1 ] && echo 1 || echo 0)"
check "⑭ order.delete audit failed" 1 "$([ "$(grep -c "audit runId=$(runid_of c14) .*toolId=order.delete .*status=failed" "$DEPLOY/backend.log")" -ge 1 ] && echo 1 || echo 0)"
gwc order.list.search 1.1.0 '{"status":"COMPLETED","limit":50}' >/dev/null; check "⑭ 10010 still listed" 1 "$(json "sum(1 for i in d['output']['items'] if i['orderId']=='10010')" < /tmp/gwc.json)"

echo "--- ⑭' 反面路径：Controller 级拦截器对 spark 无效（教学断言）"
check "⑭' guest GET /demo/orders (same capability via Controller)" 403 "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/demo/orders" -H 'X-Demo-User: guest')"
check "⑭' admin GET /demo/orders" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/demo/orders")"
curl -s -N --max-time "$SSE_T" -X POST "$BASE/agent/runs" "${GUEST[@]}" -d "{\"conversationId\":\"c14g\",\"message\":\"看看我的订单\",$CAP}" > "$DEPLOY/c14g.log"
check "⑭' guest via spark bypasses interceptor" "run.started tool.selected tool.started tool.completed ui.replace run.completed" "$(events "$DEPLOY/c14g.log")"

echo "--- ㉖ 上下文传播：工作线程里的用户 == X-Demo-User"
curl -s -X POST "$BASE/internal/tool-gateway/invoke" -H 'Content-Type: application/json' -H 'X-Demo-User: guest' -d '{"toolId":"demo.whoami","toolVersion":"1.0.0","arguments":{},"executionContext":{"runId":"run_e2e_gw","toolCallId":"tc_whoami","sessionId":"e2e","idempotencyKey":"whoami-guest"}}' > "$DEPLOY/whoami_guest.json"
check "㉖ viewer" guest "$(json "d['output']['viewer']" < "$DEPLOY/whoami_guest.json")"

echo "--- ⑮ 订单 10006 退款（无 pageContext）→ 三步 + 确认 → Result"
run_msg c15 "订单 10006 退款"
check "⑮ events" "run.started tool.selected tool.started tool.completed tool.selected tool.started tool.completed ui.replace confirmation.required" "$(events "$DEPLOY/c15.log")"
check "⑮ types" "['Card', 'Card', 'Form']" "$(types c15)"
confirm_run c15 '{"reason":"CHANGED_MIND"}' c15b
check "⑮ confirm events" "tool.selected tool.started tool.completed tool.selected tool.started tool.completed ui.replace run.completed" "$(events "$DEPLOY/c15b.log")"
check "⑮ result types" "['Result']" "$(types c15b)"
check "⑮ refunds for 10006" 1 "$(gw 10006)"

echo "--- ⑯ 删除订单（无号码）→ message.delta 友好提示"
run_msg c16 "删除订单"
# change 5：无号码「删除订单」→ 澄清屏（订单列表 + 每行「删除订单」按钮）
check "⑯ events" "run.started tool.selected tool.started tool.completed ui.replace message.delta run.completed" "$(events "$DEPLOY/c16.log")"
check "⑯ text" 1 "$(data "$DEPLOY/c16.log" message.delta | json "1 if '请选择' in d['text'] else 0")"
check "⑯ row action label" 删除订单 "$(comp c16 clarify "['rows'][0]['actions'][0]['label']")"

echo "--- ⑰ 我想查看最近订单 → order.list.search，参数全走默认 {limit:20}"
run_msg c17 "我想查看最近订单"
check "⑰ tool" order.list.search "$(data "$DEPLOY/c17.log" tool.selected | json "d['toolId']")"
check "⑰ rows" 20 "$(data "$DEPLOY/c17.log" ui.replace | json "len([c for c in d['ui']['components'] if c['id']=='orders'][0]['props']['rows'])")"

echo "--- ⑱ 最近 5 单已发货的订单 → {status:SHIPPED, limit:5}"
run_msg c18 "最近 5 单已发货的订单"
check "⑱ rows" 5 "$(data "$DEPLOY/c18.log" ui.replace | json "len([c for c in d['ui']['components'] if c['id']=='orders'][0]['props']['rows'])")"
check "⑱ all SHIPPED" 1 "$(data "$DEPLOY/c18.log" ui.replace | json "1 if all(r['cells']['status']=='SHIPPED' for r in [c for c in d['ui']['components'] if c['id']=='orders'][0]['props']['rows']) else 0")"

echo "--- ⑲ 最近 100 单 → limit 截断到 50"
run_msg c19 "最近 100 单订单"
check "⑲ rows (≤ 50, 29 left)" 29 "$(data "$DEPLOY/c19.log" ui.replace | json "len([c for c in d['ui']['components'] if c['id']=='orders'][0]['props']['rows'])")"

echo "--- ⑳ 看看我的订单 → 第二个的物流 → order.logistics.get{orderId == rows[1].id}，日志 source=memory"
run_msg c20 "看看我的订单"
ROW2=$(data "$DEPLOY/c20.log" ui.replace | json "[c for c in d['ui']['components'] if c['id']=='orders'][0]['props']['rows'][1]['id']")
run_msg c20 "第二个的物流"
check "⑳ tool" order.logistics.get "$(data "$DEPLOY/c20.log" tool.selected | json "d['toolId']")"
check "⑳ card title has row2 id" 1 "$(data "$DEPLOY/c20.log" ui.replace | json "1 if '$ROW2' in [c for c in d['ui']['components'] if c['id']=='logistics'][0]['props']['title'] else 0")"
check "⑳ source=memory logged" 1 "$([ "$(grep -c "runId=$(runid_of c20) .*source=memory" "$DEPLOY/backend.log")" -ge 1 ] && echo 1 || echo 0)"

echo "--- ㉑ 查看订单 10002 的物流 → 申请售后（省略订单号，记忆补位）→ 确认屏 Card 标题「订单 10002」"
run_msg c21 "查看订单 10002 的物流"
run_msg c21 "申请售后"
check "㉑ events" "run.started tool.selected tool.started tool.completed ui.replace confirmation.required" "$(events "$DEPLOY/c21.log")"
check "㉑ card title" "订单 10002" "$(comp c21 order "['title']")"

echo "--- ㉒ 空会话「申请售后」→ 澄清屏 Table，行内 label「申请售后」；点选 → 确认屏"
run_msg c22 "申请售后"
check "㉒ events" "run.started tool.selected tool.started tool.completed ui.replace message.delta run.completed" "$(events "$DEPLOY/c22.log")"
check "㉒ types" "['Table']" "$(types c22)"
check "㉒ clarify tool" order.list.search "$(data "$DEPLOY/c22.log" tool.selected | json "d['toolId']")"
check "㉒ row action label" 申请售后 "$(comp c22 clarify "['rows'][0]['actions'][0]['label']")"
check "㉒ row action intent has id" 1 "$(data "$DEPLOY/c22.log" ui.replace | json "(lambda r: 1 if r['id'] in r['actions'][0]['intent'] and '售后' in r['actions'][0]['intent'] else 0)([c for c in d['ui']['components'] if c['id']=='clarify'][0]['props']['rows'][0])")"
check "㉒ text" 1 "$(data "$DEPLOY/c22.log" message.delta | json "1 if '请选择' in d['text'] else 0")"
PICK=$(data "$DEPLOY/c22.log" ui.replace | json "[c for c in d['ui']['components'] if c['id']=='clarify'][0]['props']['rows'][0]['actions'][0]['intent']")
run_msg c22 "$PICK"
check "㉒ pick → confirmation" 1 "$(events "$DEPLOY/c22.log" | grep -c 'confirmation.required$')"
check "㉒ pick → types" "['Card', 'Form']" "$(types c22)"
echo "--- ㉒' 澄清屏 → 「第二个」序数指代 → 确认屏标题含 rows[1].id（评审 M-4）"
run_msg c22b "申请售后"
ROW2B=$(data "$DEPLOY/c22b.log" ui.replace | json "[c for c in d['ui']['components'] if c['id']=='clarify'][0]['props']['rows'][1]['id']")
run_msg c22b "第二个"
check "㉒' ordinal → confirmation" 1 "$(events "$DEPLOY/c22b.log" | grep -c 'confirmation.required$')"
check "㉒' card title has rows[1].id" "订单 $ROW2B" "$(comp c22b order "['title']")"
check "㉒' source=memory(ordinal) logged" 1 "$([ "$(grep -c "runId=$(runid_of c22b) .*source=memory(ordinal)" "$DEPLOY/backend.log")" -ge 1 ] && echo 1 || echo 0)"

echo "--- ㉒'' 澄清屏（删除订单）→ 「第二个的物流」：当前消息自带动词，不拼回「删除」→ 物流屏而非删除确认（评审 v2 N-2）"
run_msg c22c "删除订单"
check "㉒'' clarify shown" "['Table']" "$(types c22c)"
ROW2C=$(data "$DEPLOY/c22c.log" ui.replace | json "[c for c in d['ui']['components'] if c['id']=='clarify'][0]['props']['rows'][1]['id']")
run_msg c22c "第二个的物流"
check "㉒'' tool" order.logistics.get "$(data "$DEPLOY/c22c.log" tool.selected | json "d['toolId']")"
check "㉒'' no confirmation" 0 "$(events "$DEPLOY/c22c.log" | grep -c 'confirmation.required$')"
check "㉒'' logistics card has rows[1].id" 1 "$(data "$DEPLOY/c22c.log" ui.replace | json "1 if '$ROW2C' in [c for c in d['ui']['components'] if c['id']=='logistics'][0]['props']['title'] else 0")"

echo "--- ㉔ 令牌 sessionId 不一致：A（user_001）的确认令牌用 B（X-Demo-User: userb）的请求提交 → CONFIRMATION_REJECTED"
run_msg c24 "订单 10011 退款"
check "㉔ confirmation shown" 1 "$(events "$DEPLOY/c24.log" | grep -c 'confirmation.required$')"
curl -s -N --max-time "$SSE_T" -X POST "$BASE/agent/runs/$(runid_of c24)/actions/$(submit_id c24)" -H 'Content-Type: application/json' -H 'X-Demo-User: userb' -d "{\"confirmationToken\":\"$(token_of c24)\",\"formData\":{\"reason\":\"DAMAGED\"}}" > "$DEPLOY/c24b.log"
check "㉔ other user's session → CONFIRMATION_REJECTED" CONFIRMATION_REJECTED "$(data "$DEPLOY/c24b.log" run.failed | json "d['code']")"
check "㉔ session mismatch logged" 1 "$([ "$(grep -c "runId=$(runid_of c24) .*session mismatch" "$DEPLOY/backend.log")" -ge 1 ] && echo 1 || echo 0)"
check "㉔ B cannot read A's run" 404 "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/agent/runs/$(runid_of c24)" -H 'X-Demo-User: userb')"
check "㉔ refund.create audit in run" 0 "$(grep -c "audit runId=$(runid_of c24) .*toolId=refund.create" "$DEPLOY/backend.log")"
# 记忆按 (sessionId, conversationId) 隔离：B 用 A 的 conversationId 说「申请售后」拿不到 A 的订单号 → 澄清屏而非补位
curl -s -N --max-time "$SSE_T" -X POST "$BASE/agent/runs" -H 'Content-Type: application/json' -H 'X-Demo-User: userb' -d "{\"conversationId\":\"c21\",\"message\":\"申请售后\",$CAP}" > "$DEPLOY/c24m.log"
check "㉔ memory isolated per session (B gets clarification, not A's order)" "['Table']" "$(types c24m)"

echo "--- ㉕ 商品详情 Card 含 actions[0].intent == 有什么商品"
check "㉕ card action intent" 有什么商品 "$(comp c10 product "['actions'][0]['intent']")"
check "㉕ card action label" 返回列表 "$(comp c10 product "['actions'][0]['label']")"

echo "--- 意图路由 ⑥：order 领域缺实体 → order.list.search 回退（仅规则模式）"
if [ "$LIVE_LLM" = 1 ]; then
  echo "  - ⑥ skipped (live mode)"
else
  curl -s -N --max-time "$SSE_T" -X POST "$BASE/agent/runs" "${HDR[@]}" -d '{"conversationId":"conv_r6","message":"订单","clientCapabilities":{"uiSchemaVersion":"1.0","components":["Card","Table"]}}' > "$DEPLOY/route6_events.log"
  check "⑥ events" "run.started tool.selected tool.started tool.completed ui.replace run.completed" "$(events "$DEPLOY/route6_events.log")"
  check "⑥ tool" order.list.search "$(data "$DEPLOY/route6_events.log" tool.selected | json "d['toolId']")"
fi

echo "--- 无能力路径"
curl -s -N --max-time "$SSE_T" -X POST "$BASE/agent/runs" "${HDR[@]}" -d '{"conversationId":"conv_003","message":"今天天气怎么样","clientCapabilities":{"uiSchemaVersion":"1.0","components":["Card"]}}' > "$DEPLOY/nocap_events.log"
check "events" "run.started message.delta run.completed" "$(events "$DEPLOY/nocap_events.log")"

echo "--- 其他"
check "unknown runId → 404" 404 "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/agent/runs/run_nope/actions/x" "${HDR[@]}" -d '{"confirmationToken":"ct_xxxxxxxxxxxxxxxx","formData":{}}')"
check "§6.2.14 audit fields" 9 "$(grep -m1 'audit runId=' "$DEPLOY/backend.log" | grep -o '[a-zA-Z]*=' | wc -l | tr -d ' ')"
# 用户原文不得进日志：对本轮实际发送过的多条消息逐条 grep（评审 M-3：旧断言的短语已无人发送，恒 0）
for phrase in '帮我把订单 10001 退款' '最近 5 单已发货的订单' '删除订单 10010' '查看订单 10002 的物流' '有什么商品'; do
  check "§6.2.15 user text in log: $phrase" 0 "$(grep -c -- "$phrase" "$DEPLOY/backend.log")"
done
check "ERROR lines" 0 "$(grep -c ' ERROR ' "$DEPLOY/backend.log")"
# 冻结产物红线：变更目录内（含报告 / 评审）不得出现 LLM 网关主机名或密钥字面量（只允许写环境变量名）
if [ -n "${SPARK_LLM_BASE_URL:-}" ]; then
  LLM_HOST2=$(printf '%s' "$SPARK_LLM_BASE_URL" | sed -E 's#^[a-z]+://##; s#[/:].*$##')
  check "LIVE: LLM host not in change dir" 0 "$(grep -rl -- "$LLM_HOST2" "$DEPLOY/.." | wc -l | tr -d ' ')"
  check "LIVE: LLM key not in change dir" 0 "$(grep -rl -- "$SPARK_LLM_API_KEY" "$DEPLOY/.." | wc -l | tr -d ' ')"
  check "LIVE: LLM model not in change dir" 0 "$(grep -rl -- "$SPARK_LLM_MODEL" "$DEPLOY/.." | wc -l | tr -d ' ')"
fi
if [ "$LIVE_LLM" = 1 ]; then
  # 冻结产物红线：LLM 网关地址与密钥不得出现在日志（Spring 异常消息会带完整 URL，靠 RetryTemplate 监听器与规划器包装拦住）
  LLM_HOST=$(printf '%s' "${SPARK_LLM_BASE_URL:-}" | sed -E 's#^[a-z]+://##; s#[/:].*$##')
  check "LIVE: LLM host not in log" 0 "$(grep -c -- "$LLM_HOST" "$DEPLOY/backend.log")"
  check "LIVE: LLM key not in log" 0 "$(grep -c -- "$SPARK_LLM_API_KEY" "$DEPLOY/backend.log")"
fi
check "⑤ route decisions logged" 1 "$([ "$(grep -c 'route runId=.* source=' "$DEPLOY/backend.log")" -ge 3 ] && echo 1 || echo 0)"

pkill -f "examples/host-demo/target/host-demo.jar --server.port=$PORT"

echo "--- ㉓ 第二次启动（--spring.profiles.active=e2e-ttl，memory-ttl=1s）：记忆过期后走澄清屏而非补位"
sleep 1
(JAVA_HOME="$HOME/.jenv/versions/21" "$JAVA" -jar "$ROOT/spark-rooter/examples/host-demo/target/host-demo.jar" --server.port="$PORT" --spring.profiles.active=e2e-ttl --spark.selfcheck.enabled=false > "$DEPLOY/backend-ttl.log" 2>&1 &)
for i in $(seq 1 40); do sleep 1; curl -sf "$BASE/actuator/health" >/dev/null 2>&1 && break; done
run_msg c23 "查看订单 10002 的物流"
sleep 2
run_msg c23 "申请售后"
check "㉓ after ttl → clarification table" "['Table']" "$(types c23)"
check "㉓ after ttl → text" 1 "$(data "$DEPLOY/c23.log" message.delta | json "1 if '请选择' in d['text'] else 0")"
pkill -f "examples/host-demo/target/host-demo.jar --server.port=$PORT"

echo; echo "e2e-backend: $pass passed, $fail failed"
[ "$fail" -eq 0 ]

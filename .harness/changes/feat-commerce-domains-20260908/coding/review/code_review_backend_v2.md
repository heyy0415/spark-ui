# Code Review v2（backend + contracts + harness）— feat-commerce-domains-20260908

- mode: execution
- 评审对象：round-1 回修 `git diff a598f43..HEAD -- backed .harness/scripts .harness/contracts`（提交 `21df695`，另含 `c880f08` 前端回修不在本评审范围）
- 独立性：未阅读 `coding/coding_report_v1.md`；只对照代码、Manifest、脚本与冻结产物，并在 8093 端口起了一个临时实例做行为探测（结束后已 kill，未触碰 8080 / 5173）。
- 依据：round-1 `code_review_backend_v1.md` 的 M-1 / M-2 / S-1..S-8；backend-standard / agent-safety / contracts。

## 0. 机械校验（本轮实际执行）

| 项 | 结果 |
|---|---|
| `backed/./mvnw -q -B verify` | 退出码 0 |
| `node .harness/scripts/check-contracts.mjs` | 0（9 schemas / 26 examples） |
| `node .harness/scripts/check-module-deps.mjs` | 0 |
| `node .harness/scripts/check-seed.mjs` | 0 |
| 全仓 grep LLM 网关主机名 / 密钥前缀 / 模型名三模式（排除 node_modules / target / .git / dist，**含 `.harness/changes/**`**） | **0 命中**（grep 退出码 1） |
| `grep -rn '"spring-ai:" + model\|model={}' backed/agent-runtime/src` | 0 命中 |
| 临时实例（`--server.port=8093`）启动自检 | 7 项全过：confirmation coverage OK (3 tools)、intent verbs reference registered tools OK、plan 5 messages OK；日志 0 条 ERROR，无模型名 |
| 冻结产物 `deployment/e2e-backend-rule.out` / `-live.out` | 108 / 113 passed，0 failed；两份均含 `✓ confirm screen shows real order status: SHIPPED`；live 含 `LLM host/key/model not in change dir: 0` 三条 |
| 工作区 | `git status` 干净（评审未修改任何被评审文件） |

## 1. round-1 条目复核

| # | 状态 | 证据 |
|---|---|---|
| M-1 退款确认屏虚构订单状态 | **已修复** | `RefundScreens.confirmation`：删除 `order.detail.get` 读取与 `"PAID"` / `"订单 xxx"` 兜底；`eligibility` / `preview` 任一缺失即 `IllegalStateException`（fail-closed）；四个订单字段只在 `hasNonNull` 时渲染，无任何业务默认值。`refund.eligibility.check` 升 1.3.0：outputSchema `additionalProperties:false`，required 8 字段 `orderId/eligible/refundableAmount/currency/orderStatus/productName/quantity/orderAmount` + 可选 `reason`，与 `RefundEligibilityCheckHandler.handle` 的 `n.put(...)` 逐键一致（`orderStatus` enum 不含 DELETED，`OrderSnapshotAdapter` 已过滤 DELETED，一致）。8093 实测 10001–10008 输出均过 Gateway outputSchema 校验且 `orderStatus` 为真实种子状态（10002=SHIPPED、10005=COMPLETED、10007=REFUNDED）。冻结 `deployment/run2_events.log:23` 订单 Card = `商品 蓝牙耳机 / 件数 1 / 金额 299.00 CNY / 状态 SHIPPED`；`e2e-backend.sh:115` 新增断言 `confirm screen shows real order status = SHIPPED`，两份 .out 均 ✓。`RefundScreens.probeOutputs` 样例逐字段符合 1.3.0 / preview 1.3.0 schema（enum 内、integer≥1、金额 pattern）。契约示例 `sse-events.tool-selected.example.json`、e2e 探针、README 同步到 1.3.0。 |
| M-2 冻结产物 secret grep 非 0 | **已修复** | 三模式全仓 grep 0（含 changes 目录）。`LlmConfiguration:61` 只记 `completionsPath`；`SpringAiLlmClient.name()` / `SpringAiIntentClassifier.name()` 改为常量 `"spring-ai"`，8093 日志无模型名。门禁：`e2e-backend.sh:278-285` 在设置 `STRATO_LLM_BASE_URL` 时对整个 change 目录 grep 主机名 / 密钥 / 模型名，live .out 三条为 0。注意该门禁**仅 LIVE 环境生效**（rule 模式不跑），见 N-5。 |
| S-1 trustedArgs 互斥自检空集恒过 | **已修复** | `ConfirmationRecheck` 新增 `Set<String> trustedArgKeys()`（静态声明）；`RefundRecheck` 返回 `{"amount"}`，Order / Aftersale 返回空集；`ConfirmationCoverageSelfCheck:97` 改用 `rc.trustedArgKeys()`，并用 `screens.probeOutputs(toolId)` 代替空 map（否则 M-1 的 fail-closed 会让自检抛异常）。运行期 `executeConfirmed:348` 仍 `putAll(rc.trustedArgs(recheck))`（值来自重校验），未再断言 keySet ⊆ trustedArgKeys，见 N-7（INFO）。 |
| S-2 契约声明的 cells ⊆ columns 校验不存在 | **已修复** | `ScreenRegistry.assertTableCells` 在 `toUi` 内、schema 校验之后执行；result 与 confirmation 两条出屏路径都经 `toUi`；违反抛 `IllegalStateException`，与契约描述一致。 |
| S-3 动词表与路由表顺序打架 / `fallbackTarget` 指向不存在工具 | **已修复** | `IntentVerbs.target` 先只在 `t.startsWith(domain + ".")` 的动词内匹配，再退回全表。8093 实测：「订单 10002 退货退款」→ 路由 refund → 计划 `refund.eligibility.check → refund.preview → refund.create`，出确认屏（状态 SHIPPED）；「订单 10002 退货」→ aftersale → `aftersale.list.get → aftersale.create`。新增 `DETAIL_TOOL`（refund→`refund.status.get`、aftersale→`aftersale.list.get`），`fallbackTarget(_, true)` 由它解析；`referencedToolIds()` 已并入 `DETAIL_TOOL.values()`，启动自检 intent verbs OK。实测「refund 订单 10001」→ `refund.status.get` → run.completed。建议中的 PlanSelfCheck「退货退款」用例**未加**（N-3，LOW）。 |
| S-4 订单号无右边界 | **已修复** | `EntityExtractor` 两个 Pattern 加 `(?!\\d)`；8093 实测「订单 100021 的物流」→ 不再抽出 10002，走 MissingEntity 友好提示「请先选择一个订单」。 |
| S-5 LIVE 不校验实体参数值 | **已修复** | `ToolSelectionValidator.validate(..., Map<String,String> entities)`：凡 `ENTITY_ARGS` 键，值 ≠ `entities.get(type)` → `TOOL_SELECTION_INVALID`（进 SpringAi 重试）。三个生产调用点均传 `req.entities()`：`SpringAiLlmClient:52`、`RuleBasedLlmClient:53`；`PlanSelfCheck:119` 传 `Map.of("order","10003")`，`:108` 用四参重载（该用例只测 toolId 越界、无 args，不受影响）。四参重载语义为「不允许任何实体参数」，是个易踩的 footgun（N-4，LOW）。 |
| S-6 pkill 误杀用户实例 | **已修复** | `e2e-backend.sh:25, 294` 与 `deploy-verify.sh:14` 的 `pkill -f` 模式均带 `--server.port=$PORT`，与启动行 `-jar .../app.jar --server.port="$PORT"` 匹配；`PORT` 在 cleanup 定义之前赋值（deploy-verify 修正了原先顺序）。`deploy-verify` 的 `pkill -f "vite preview"` 仍无条件执行（4173 为固定端口，影响面小，N-6 LOW）。 |
| S-7 解析失败被当传输错误不重试 | **部分** | 解析失败（`.entity()` 抛 RuntimeException）现进入 `TOOL_SELECTION_INVALID` 并重试 ✓，日志只记类名 ✓。但「仅 `RestClientException` 走 INTERNAL_ERROR」的划分**漏掉 Spring AI 自己的 HTTP 错误类型**：`OpenAiApi` 默认 `RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER` 对 4xx 抛 `NonTransientAiException`、5xx 抛 `TransientAiException`，二者直接 `extends RuntimeException`（spring-ai-retry 1.1.8 源码），**不是** `RestClientException`。因此网关 401 / 429 / 5xx 会落入「planner output unparseable」分支：错误码变成 `TOOL_SELECTION_INVALID`（用户看到「无法制定方案」而非内部错误），且在 `fastFailRetry` 2 次之上再乘规划器 2 次 = 最多 4 次 HTTP 调用。只有 `ResourceAccessException`（连接拒绝 / 超时）仍正确走 INTERNAL_ERROR。见 N-1。 |
| S-8 stepOutputs 泄漏 | **部分** | `complete()` / `fail()` 均 `stepOutputs.remove(run.runId())` ✓（与 `confirm()` 终态的 remove 重复但无害）。`lastUi` 仍无 TTL / 上限（`InMemoryRunRepository` 也无上限，二者「同生命周期」实际都是永久），首期内存实现可接受，记 N-8 LOW。 |

## 2. 新发现

| # | 位置 | 问题 | 建议 | 分级 |
|---|---|---|---|---|
| N-1 | `backed/agent-runtime/src/main/java/com/strato/runtime/infra/llm/SpringAiLlmClient.java:64-75` | 见 S-7「部分」：`TransientAiException` / `NonTransientAiException`（Spring AI 对 HTTP 4xx/5xx 的包装，`extends RuntimeException`）被归入「模型输出不可解析」分支，错误码错、重试翻倍。异常消息含响应体但代码只记类名，无泄漏。 | 传输分支同时捕获 `org.springframework.ai.retry.TransientAiException` / `NonTransientAiException`（或统一按 `!(e instanceof 解析类异常)` 划分：把 `.call().content()` 与 `mapper.readValue` 拆开，只有后者的 `JsonProcessingException` / `IllegalStateException` 进重试）。 | SHOULD |
| N-2 | `backed/domains/refund-service/src/main/java/com/strato/domain/refund/infra/RefundEligibilityCheckHandler.java:13` | 类 Javadoc 仍写 `refund.eligibility.check@1.2.0`，`version()` 已是 1.3.0。 | 改注释。 | LOW |
| N-3 | `backed/agent-runtime/src/main/java/com/strato/runtime/infra/selfcheck/PlanSelfCheck.java:36-56, 105-125` | S-3 / S-5 的修复只有行为、没有启动自检覆盖：CASES 无「退货退款」（域内优先匹配）用例；validator 用例无「实体参数值 ≠ 识别实体 → 拒绝」用例。回归时不会被自检捕获。 | 加 `Case("refund","订单 10002 退货退款",{order:10002},[eligibility, preview, refund.create])` 与一条 `DraftStep("refund.status.get", {orderId:"10009"})` + `entities={order:10001}` 期望 RunFailure。 | LOW |
| N-4 | `ToolSelectionValidator.validate` 四参重载（`:62-69`） | 委托 `Map.of()`，语义等于「凡实体参数一律拒绝」，与方法名不符；今后新调用者若沿用会把合法计划全部打回。 | 删掉四参重载，调用点显式传 entities；或改名 `validateNoEntities`。 | LOW |
| N-5 | `.harness/scripts/e2e-backend.sh:278-285`；`.harness/scripts/ci.mjs` / `harness-doctor.mjs` | M-2 的「change 目录不得含网关 / 密钥 / 模型字面量」门禁只在设置了 `STRATO_LLM_BASE_URL` 时执行；`pnpm -C .harness run ci`（rule 模式）与 `doctor` 都不跑，本地无 LLM 环境的提交可再次把字面量写进报告而门禁不响。 | 在 `harness-doctor.mjs` 增加对 `.harness/changes/**` 的密钥形态扫描（如 `sk-[A-Za-z0-9]{8,}` 与内部网关域名后缀正则，模式本身用拼接避免自命中，同文件已有此技法 `:194`）。 | LOW |
| N-6 | `.harness/scripts/deploy-verify.sh:14` | `pkill -f "vite preview"` 仍无条件，会杀掉用户自己起的任何 `vite preview`。 | 只 kill 本脚本记录的 PID，或限定 `--port 4173`。 | LOW |
| N-7 | `RunOrchestrator.executeConfirmed:348` | `trustedArgKeys()` 只用于启动自检；运行期未断言 `rc.trustedArgs(recheck).keySet() ⊆ rc.trustedArgKeys()`，领域实现若两者不一致（声明漏键）不会被发现。 | `putAll` 前加一行断言，违反抛 INTERNAL_ERROR。 | INFO |
| N-8 | `RunOrchestrator:83` `lastUi` | 无 TTL / 上限（S-8 部分）。首期 in-memory，与 `InMemoryRunRepository` 一致。 | 与 Run 仓储同引入淘汰策略时一并处理。 | LOW |
| N-9 | `IntentVerbs.VERBS` 最后一条 `<domain>.detail.get` | 路由到 refund / aftersale 且消息只含「详情」类动词而无领域动词（如「refund 订单 10001 详情」）时展开为 `refund.detail.get`，不在候选 → preflight `TOOL_SELECTION_INVALID`。与已修的 `fallbackTarget` 同源问题的另一入口，实际触达概率低。 | `target()` 对 `<domain>` 占位改用 `DETAIL_TOOL.get(domain)` 解析。 | LOW |

## 3. 结论

- round-1 两条 MUST FIX 均已关闭，且有冻结产物 + 脚本断言 + 启动自检三重证据。
- 8 条 SHOULD：6 条已修复，S-7 / S-8 部分（S-7 遗留的异常分类错误记为新 SHOULD N-1，不构成安全红线）。
- 新发现无 MUST FIX。

**verdict: APPROVED**（附 1 条 SHOULD N-1 与 7 条 LOW / INFO，建议随下一次 runtime 改动一并处理；N-1 因涉及 LIVE 错误码语义，建议进入阶段 5 前顺手修）

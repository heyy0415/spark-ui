# Tasks: feat-intent-routing-20260908

> v2 — 响应 `review/spec_review_v1.md`：T02 自检只测 store + latch、删 putIfAbsent、deploy-verify 期望 5；T03 `domains(principal)` + 领域说明表 + entityType 白名单；T04 拆 T04a（Resolver / Check）与 T04b（规则规划器模板 + 种子 10004）；T05 含 check-registry 正则 + ActionBarProps 放 registry/types；T07 拆 T07a（doctor）/ T07b（e2e ①–⑥）。11 task。
> v1。所属端 backed / fronted / harness；contracts 无改动。每个 task ≤ 0.5 天。编码顺序：Phase A（后端接口与幂等，独立可验证）→ Phase B（路由与拦截）→ Phase C（前端遗留）→ Phase D（Harness / 文档）→ T09 全链路。

## Phase A — 后端评审遗留

### T01 Registry / Gateway 抽接口层（N4）
- **目标**：`tool-registry/api/ToolSearchPort`、`tool-gateway/api/ToolInvokePort`（后者失败返回 `Response.failed`，即现 `executeToResponse` 语义）；`SearchToolsUseCase implements ToolSearchPort`、`InvokeToolUseCase implements ToolInvokePort`；`agent-runtime/infra/inprocess/*` 只依赖接口；`check-module-deps.mjs` 增规则「runtime 不得 import `com.strato.(registry|gateway).application.`」；`project-structure.md` §2 / `backend-standard.md` §4 / `agent-runtime/pom.xml` 注释恢复「api 包接口」措辞。
- **所属端**：backed / harness
- **输入**：`backed/agent-runtime/src/main/java/com/strato/runtime/infra/inprocess/*.java`、`tool-registry/…/application/SearchToolsUseCase.java`、`tool-gateway/…/application/InvokeToolUseCase.java`、`.harness/scripts/check-module-deps.mjs`
- **输出**：2 个新接口、3 个改动类、脚本与 3 份文档
- **验收**：`mvn verify` 0；`grep -rn "\.application\." backed/agent-runtime/src --include=*.java | grep "registry\|gateway"` 0 行；植入 `import com.strato.registry.application.SearchToolsUseCase;` 到 `InProcessToolRegistryClient` → check-module-deps 红，还原绿
- **依赖**：—

### T02 Gateway 幂等先占位后填充（N6）
- **目标**：`IdempotencyStore` 重定义为 `claim → Claim{Owner|Replay|Awaiting}` / `complete` / `release` / `find`，删 `putIfAbsent`；`InMemoryIdempotencyStore` 用 `ConcurrentHashMap<String, CompletableFuture<Response>>`，`release` = `completeExceptionally` + remove；`InvokeToolUseCase.pipeline` 第 4 步按 spec §2.3（Owner 执行 / Replay 与 Awaiting 完成审计 `status=replayed` / Awaiting 异常重新 claim 一次 / 超时 TIMEOUT）。新增 `gateway/infra/selfcheck/GatewayIdempotencySelfCheck`：只测 store，`CountDownLatch` 保证 B 在 A complete 前 claim；覆盖 Owner→Awaiting→同结果 与 release→重新 claim 两条路径。`deploy-verify.sh:29` 与 `e2e-backend.sh` selfcheck 期望 4 → 5。
- **所属端**：backed
- **输入**：`tool-gateway/…/domain/IdempotencyStore.java`、`infra/InMemoryIdempotencyStore.java`、`application/InvokeToolUseCase.java`、spec §2.3 / §4.5
- **输出**：上述 3 文件 + 新自检类
- **验收**：`mvn verify` 0；启动日志 `selfcheck: gateway idempotency claim OK`；`SelfCheckRunner` `running 5 checks`；`grep -n putIfAbsent backed/tool-gateway/src` 0 行
- **依赖**：—

## Phase B — 路由与拦截

### T03 IntentClassifier 端口与两个实现
- **目标**：`application/port/IntentClassifier`；`infra/llm/SpringAiIntentClassifier`（record `IntentDraft(String domain)`，Prompt：领域枚举 + 一句话说明 + 用户消息（sanitize）+ 可选「用户正在查看一个 {entityType}」；`temperature 0`；`internalToolExecutionEnabled(false)`；解析失败 / 非枚举 → `Optional.empty()` + WARN 不含原文）；`infra/llm/NoopIntentClassifier`；`LlmConfiguration` 按同三个环境变量装配二选一。`ToolRegistryClient` 增 `Set<String> domains(ToolSearch.Principal)`（Registry 侧 = 全部 manifest 经 `DiscoveryPolicy.filter(…, perms)` 后取 `domain` 去重，经 T01 的 `ToolSearchPort` 暴露；接口 Javadoc 写明「发现面两个只读查询均按 principal 过滤」）；`application/DomainDescriptions`（领域 → 一句话说明，硬编码）；分类 Prompt 明示闲聊输出 none；`entityType` 只在白名单 `{order}` 内才附提示。
- **所属端**：backed
- **输入**：T01、`infra/llm/{SpringAiLlmClient,PromptBuilder,LlmConfiguration}.java`、spec §2.1 / §4.4
- **输出**：4 个新类 + `LlmConfiguration`、`ToolRegistryClient`、`ToolSearchPort`、`InProcessToolRegistryClient` 改动
- **验收**：`mvn verify` 0；`grep -n "internalToolExecutionEnabled(false)" …/SpringAiIntentClassifier.java` 1；`grep -n "log\.\(info\|warn\)" …/SpringAiIntentClassifier.java | grep -i "message"` 0 行（不落原文）
- **依赖**：T01

### T04a DomainResolver 与 EntityRequirementCheck
- **目标**：`application/DomainResolver`（rule → classifier → none，产出 `RouteDecision(domain, source)`）；`application/EntityRequirementCheck`（按候选 `inputSchema.required` 与 `selectedEntity` 判定，映射 `orderId ↔ type=order`；全部候选需实体且缺失 → `Optional<String>` 文案）；`RunOrchestrator.start`：resolve → 空则无能力路径 → search → Check 命中则 `message.delta` + complete（终态 COMPLETED）→ plan。日志 `route runId={} domain={} source={}`。
- **所属端**：backed
- **输入**：T03、`application/RunOrchestrator.java`、`domain/DomainRouter.java`、spec §2.1 / §2.2 / §4
- **输出**：2 个新类 + `RunOrchestrator`
- **验收**：`mvn verify` 0；`curl POST /agent/runs`（无 pageContext，「退钱」）事件 `run.started message.delta run.completed`、text 含「选择一个订单」不含「退钱」；`grep -c "audit runId=<runId>"` 0；summary COMPLETED 无 failureCode；`curl`（`entityType=order`，「今天天气怎么样」）→ 无能力路径
- **依赖**：T03

### T04b 规则规划器缺实体修正与种子订单
- **目标**：`RuleBasedLlmClient` 模板按实体有无二选一（order 无 orderId → `order.list.search {}`），缺必填实体的步骤不生成，全跳过 → `TOOL_SELECTION_INVALID`；`InMemoryOrderRepository` 与 `SeededOrderLookup` 新增订单 `10004`（PAID，59.00）。
- **所属端**：backed
- **输入**：T04a、`infra/llm/RuleBasedLlmClient.java`、两个种子文件
- **输出**：3 个改动文件
- **验收**：`mvn verify` 0；`curl`（无 pageContext，「订单」）→ 事件含 `tool.selected` 且 `toolId == order.list.search`，终态 `run.completed`；`refund.status.get 10004` 经 Gateway 返回 0 条（未退款）
- **依赖**：T04a

## Phase C — 前端评审遗留

### T05 core 内部去重与冻结
- **目标**：`registry/types.ts` 删 `FormFieldPropsSchema` / `FormComponentPropsSchema`，改 import `schema/uiSchema` 的 `FormFieldSchema` / `FormPropsSchema`，`FormComponentProps = z.infer<typeof FormPropsSchema>`；`Object.freeze(PROPS_SCHEMAS)` 且 `scripts/check-registry.mjs` 正则改为 `(?:Object\.freeze\()?\{…\}(?: as const)?\)?;`；`ActionBarProps` 移到 `registry/types.ts`，renderer 与两端实现 `import type`，`index.ts` 改导出路径；`apps/chat` `RouteErrorBoundary` 标题空串回退 `'页面出错了'`。
- **所属端**：fronted
- **输入**：`fronted/packages/core/src/registry/types.ts`、`renderer/ActionBar.tsx`、`components/{desktop,mobile}/ActionBar.tsx`、`src/index.ts`、`fronted/scripts/check-registry.mjs`、`apps/chat/src/app/router/RouteErrorBoundary.tsx`
- **输出**：上述 7 文件
- **验收**：`pnpm -C fronted run ci` 0；spec §6.2 三条 grep；负例：`PROPS_SCHEMAS` 临时改回非 freeze 形态 check-registry 仍绿；verify-pack 仍 17 + 10；`node .harness/scripts/e2e-frontend.mjs` 21/21
- **依赖**：—

### T06 verify-pack 基线显式化
- **目标**：(f) 基线缺失 → `✗ baseline missing — run \`pnpm -C fronted run verify-pack -- --write-baseline\``；`process.argv.includes('--write-baseline')`（pnpm 透传后 argv 含 `--`）时写入并 ✓。
- **所属端**：fronted
- **输入**：`fronted/scripts/verify-pack.mjs`
- **输出**：同文件
- **验收**：spec §6.2 第 4 条（移走基线 → 红；`--write-baseline` → 绿；还原）
- **依赖**：—

## Phase D — Harness 与文档

### T07a doctor L1 一致性检查
- **目标**：`harness-doctor.mjs` 新检查：`CLAUDE.md` / `AGENTS.md` / `platform-owner.md` 的 antd 约束行含 `packages/core/src/components/**` 且全文无 `shared/ui/**`。
- **所属端**：harness
- **输入**：`.harness/scripts/harness-doctor.mjs`、spec §2.4
- **输出**：同文件
- **验收**：doctor 0；植入 `CLAUDE.md` 改回 `shared/ui/**` → 红，还原绿
- **依赖**：—

### T07b e2e 新用例
- **目标**：`e2e-backend.sh` 新增 ①②④⑤⑥（两模式）与 ③a/③b（LIVE 才跑，否则 `skipped (rule mode)`）；selfcheck 期望 4 → 5。
- **所属端**：harness
- **输入**：T02、T04b、`.harness/scripts/e2e-backend.sh`、spec §2.5 / §6.1
- **输出**：同文件
- **验收**：规则模式全绿；LIVE 模式全绿且 `grep -c "source=model"` ≥ 1（记入 coding_report）
- **依赖**：T02、T04b

### T08 文档同步
- **目标**：`agent-safety.md` §2、`wiki/architecture.md`（框图 + 运行链路第 2 步）、`backend-standard.md` §4 / §7、`project-structure.md` §2、`backed/README.md`（意图分类一节 + 幂等说明）、`backed/agent-runtime/README.md`（若存在）、`06-backend-module-spec.md`（若含 agent-runtime 段）。
- **所属端**：harness
- **输入**：T01–T04b 实际产出
- **输出**：上述文档
- **验收**：`grep -n "领域路由（规则）" .harness/wiki/architecture.md` 0；`grep -n "模型补位" .harness/rules/agent-safety.md` ≥ 1；doctor 0
- **依赖**：T07a、T07b

### T09 全链路验收
- **目标**：规则模式 e2e-backend；有 LLM 变量时再跑一次（用例 ③ 生效，`source=model` ≥ 1）；e2e-frontend；deploy-verify；`rm -rf fronted/*/dist && pnpm -C .harness run ci`。产物写入本 change `deployment/`。
- **所属端**：harness
- **输入**：T08
- **输出**：`deployment/**`、`coding/coding_report_v1.md`
- **验收**：spec §6 全部为真，真实退出码记入 coding_report
- **依赖**：T08

## 依赖图

```
T01 → T03 → T04a → T04b ─┐
T02 ─────────────────────┼→ T07b ─┐
T07a ────────────────────┘        ├→ T08 → T09
T05, T06 ─────────────────────────┘
```

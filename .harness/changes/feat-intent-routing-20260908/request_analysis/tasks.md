# Tasks: feat-intent-routing-20260908

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
- **目标**：`IdempotencyStore` 增 `claim / complete / release`（保留 `find` 供只读查询）；`InMemoryIdempotencyStore` 用 `ConcurrentHashMap<String, CompletableFuture<Response>>`；`InvokeToolUseCase.pipeline` 第 4 步改为 claim；成功 complete，异常 `finally` release；等待方超时 → `GatewayException(TIMEOUT)`。新增 `gateway/infra/selfcheck/GatewayIdempotencySelfCheck`（用一个人工 `ToolHandler` 记录调用次数，两线程同 key 并发 → 次数 1、两响应同 `toolCallId`）。
- **所属端**：backed
- **输入**：`tool-gateway/…/domain/IdempotencyStore.java`、`infra/InMemoryIdempotencyStore.java`、`application/InvokeToolUseCase.java`、spec §2.3 / §4.5
- **输出**：上述 3 文件 + 新自检类
- **验收**：`mvn verify` 0；启动日志 `selfcheck: gateway idempotency claim OK`；`SelfCheckRunner` 日志 `running 5 checks`
- **依赖**：—

## Phase B — 路由与拦截

### T03 IntentClassifier 端口与两个实现
- **目标**：`application/port/IntentClassifier`；`infra/llm/SpringAiIntentClassifier`（record `IntentDraft(String domain)`，Prompt：领域枚举 + 一句话说明 + 用户消息（sanitize）+ 可选「用户正在查看一个 {entityType}」；`temperature 0`；`internalToolExecutionEnabled(false)`；解析失败 / 非枚举 → `Optional.empty()` + WARN 不含原文）；`infra/llm/NoopIntentClassifier`；`LlmConfiguration` 按同三个环境变量装配二选一。`ToolRegistryClient` 增 `Set<String> domains()`（`SearchToolsUseCase` 侧由 `ToolRegistryRepository` 提供去重领域集合，经 T01 的 `ToolSearchPort` 暴露）。
- **所属端**：backed
- **输入**：T01、`infra/llm/{SpringAiLlmClient,PromptBuilder,LlmConfiguration}.java`、spec §2.1 / §4.4
- **输出**：4 个新类 + `LlmConfiguration`、`ToolRegistryClient`、`ToolSearchPort`、`InProcessToolRegistryClient` 改动
- **验收**：`mvn verify` 0；`grep -n "internalToolExecutionEnabled(false)" …/SpringAiIntentClassifier.java` 1；`grep -n "log\.\(info\|warn\)" …/SpringAiIntentClassifier.java | grep -i "message"` 0 行（不落原文）
- **依赖**：T01

### T04 DomainResolver 与 EntityRequirementCheck
- **目标**：`application/DomainResolver`（rule → classifier → none，产出 `RouteDecision(domain, source)`；`entityType` 只作为分类提示，不单独判定）；`application/EntityRequirementCheck`（按候选 `inputSchema.required` 与 `selectedEntity` 判定，映射 `orderId ↔ type=order`；返回 `Optional<String>` 提示文案）；`RunOrchestrator.start` 改为：resolve → 空则无能力路径 → search → EntityRequirementCheck 命中则 `message.delta(文案)` + complete → plan。日志 `route runId={} domain={} source={}`。`RuleBasedLlmClient`：`orderId` 缺失时跳过需 `orderId` 的模板步骤；全跳过 → `TOOL_SELECTION_INVALID`。
- **所属端**：backed
- **输入**：T03、`application/RunOrchestrator.java`、`domain/DomainRouter.java`、`infra/llm/RuleBasedLlmClient.java`、spec §2.1 / §2.2 / §4
- **输出**：2 个新类 + 2 个改动类
- **验收**：`mvn verify` 0；`curl POST /agent/runs`（无 pageContext，「退款」）事件 `run.started message.delta run.completed` 且 text 含「选择一个订单」；`grep -c "audit runId=<runId>"` 0；`curl`（`entityType=order`，「今天天气怎么样」）→ 无能力路径
- **依赖**：T03

## Phase C — 前端评审遗留

### T05 core 内部去重与冻结
- **目标**：`registry/types.ts` 删 `FormFieldPropsSchema` / `FormComponentPropsSchema`，改 import `schema/uiSchema` 的 `FormFieldSchema` / `FormPropsSchema`，`FormComponentProps` 类型改为 `z.infer<typeof FormPropsSchema>`；`Object.freeze(PROPS_SCHEMAS)`（check-registry 正则兼容）；`ActionBarProps` 只在 `renderer/ActionBar.tsx` 定义，desktop / mobile `import type`；`apps/chat` `RouteErrorBoundary` 标题空串回退。
- **所属端**：fronted
- **输入**：`fronted/packages/core/src/registry/types.ts`、`renderer/ActionBar.tsx`、`components/{desktop,mobile}/ActionBar.tsx`、`apps/chat/src/app/router/RouteErrorBoundary.tsx`
- **输出**：上述 5 文件
- **验收**：`pnpm -C fronted run ci` 0；spec §6.2 三条 grep；`node .harness/scripts/e2e-frontend.mjs` 21/21
- **依赖**：—

### T06 verify-pack 基线显式化
- **目标**：(f) 基线缺失 → `✗ baseline missing — run \`pnpm -C fronted run verify-pack -- --write-baseline\``；新增 `--write-baseline` 写入并 ✓。
- **所属端**：fronted
- **输入**：`fronted/scripts/verify-pack.mjs`
- **输出**：同文件
- **验收**：spec §6.2 第 4 条（移走基线 → 红；`--write-baseline` → 绿；还原）
- **依赖**：—

## Phase D — Harness 与文档

### T07 doctor L1 一致性检查 + e2e 新用例
- **目标**：`harness-doctor.mjs` 新检查：三份 L1 文件的 antd 约束行含 `packages/core/src/components/**` 且无 `shared/ui/**`；`e2e-backend.sh` 新增用例 ①②③④⑤（③ 在 `LIVE_LLM=0` 时打印 `skipped (rule mode)`），selfcheck 期望 4 → 5。
- **所属端**：harness
- **输入**：T02、T04、`.harness/scripts/{harness-doctor.mjs,e2e-backend.sh}`、spec §2.5 / §6.1
- **输出**：两个脚本
- **验收**：doctor 0；植入 `CLAUDE.md` 改回 `shared/ui/**` → 红；e2e-backend 全绿（规则模式）
- **依赖**：T02、T04

### T08 文档同步
- **目标**：`agent-safety.md` §2、`wiki/architecture.md`（框图 + 运行链路第 2 步）、`backend-standard.md` §4 / §7、`project-structure.md` §2、`backed/README.md`（意图分类一节 + 幂等说明）、`backed/agent-runtime/README.md`（若存在）、`06-backend-module-spec.md`（若含 agent-runtime 段）。
- **所属端**：harness
- **输入**：T01–T04 实际产出
- **输出**：上述文档
- **验收**：`grep -n "领域路由（规则）" .harness/wiki/architecture.md` 0；`grep -n "模型补位" .harness/rules/agent-safety.md` ≥ 1；doctor 0
- **依赖**：T07

### T09 全链路验收
- **目标**：规则模式 e2e-backend；有 LLM 变量时再跑一次（用例 ③ 生效，`source=model` ≥ 1）；e2e-frontend；deploy-verify；`rm -rf fronted/*/dist && pnpm -C .harness run ci`。产物写入本 change `deployment/`。
- **所属端**：harness
- **输入**：T08
- **输出**：`deployment/**`、`coding/coding_report_v1.md`
- **验收**：spec §6 全部为真，真实退出码记入 coding_report
- **依赖**：T08

## 依赖图

```
T01 → T03 → T04 ─┐
T02 ─────────────┼→ T07 → T08 → T09
T05, T06 ────────┘
```

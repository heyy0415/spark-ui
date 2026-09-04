# Spec: feat-agent-tool-platform-20260903

> v3.1 — v3 经 `review/spec_review_v3.md` APPROVED；吸收其 SHOULD/LOW 项（P01–P17），不改变范围。
> v3.2 — 阶段 4 `coding/review/code_review_v2.md` 回写：§4.2 Gateway 失败映射与 502、§5 `error-response.details[]` / `run-summary.failureCode`、§6.2 新增 M1/M2/M3 反例与 domain 包检查措辞、§6.3.4 第 4 步 console.error 措辞。不改变范围。

## 1. 背景

业务侧希望用户在页面上用自然语言表达意图（如"帮我把这个订单退款"），由 AI 完成理解、规划与执行，同时保证企业内部工具的调用**可发现、可控制、可恢复、可审计**。

现有仓库只有一个前端空壳（`fronted/`，Vite 8 / React 19 / TS 7）和一个空的 `backed/` 目录。本 change 建立首期最小可运行平台，确立四个面（决策 / 控制 / 执行 / 状态）与前端 Generate UI 的边界，并用一条端到端场景"给订单 10001 退款"验证整条链路。

> 一句话定位：Generate UI 负责交互，Agent Runtime 负责理解与规划，Tool Registry 负责能力发现与治理（控制面），Tool Gateway 负责安全执行（执行面），领域服务负责确定性业务执行。

## 2. 范围（In Scope）

### 2.1 契约（`.harness/contracts/`）
9 个 JSON Schema 2020-12 文件及 20 个示例，清单见 §5。

### 2.2 后端（`backed/`，Java 21 / Spring Boot 3.5 / Spring AI 1.1 / Maven 多模块，单进程装配）

模块与依赖方向以 `project-structure.md` §2 为准。

- `platform-spi`：接口层，**零 Spring 依赖，允许 Jackson**（`JsonNode` 承载自由 JSON，符合 `backend-standard.md` §2）：
  - `ToolHandler { String toolId(); String version(); JsonNode handle(JsonNode args, ExecutionContext ctx); }`
  - `ToolManifestSource { List<JsonNode> manifests(); }`（领域模块暴露 Bean，Registry 启动时拉取；领域模块因此**不依赖** registry）
  - `ToolResolver`（Registry 提供给 Gateway：`toolId@version → manifest`）
  - `PrincipalPermissionResolver { Set<String> permissionsOf(Principal p); }`
  - `SelfCheck { String name(); void run(); }`（各模块在自己 `infra/selfcheck/` 提供 Bean）
  - `ExecutionContext`、`Principal` record
- `contracts-java`：9 组 record DTO + `SchemaValidator`（networknt，构建时把 `.harness/contracts/*.schema.json` 与 `examples/` 复制进 resources）。
- `tool-registry`：内存存储；`POST /internal/tool-registry/tools`（同 `toolId@version` 重复 → 409 `TOOL_VERSION_CONFLICT`）、`POST /search`（按 tenant / permission / `status ∈ {active, canary}` 过滤，响应项**只含**六字段）、`GET /tools/{toolId}/versions`；`ApplicationReadyEvent` 时遍历所有 `ToolManifestSource` 完成启动注册；实现 `ToolResolver`。**无**转发端点、**无**出向 HTTP 客户端。
- `tool-gateway`：`POST /internal/tool-gateway/invoke`；顺序：输入 Schema 校验 → `PrincipalPermissionResolver` 鉴权 → 幂等（`tenantId + idempotencyKey` 内存表）→ `ToolResolver` 寻址 → 注入的 `List<ToolHandler>` 匹配并调用（超时按 `execution.timeoutMs`；按 `execution.maxRetries` 重试，仅当 `idempotency = required` 或 `sideEffect = false`）→ 输出 Schema 校验 → 脱敏 → 审计日志。pom **不**依赖 `domains/*`。熔断 / 限流见 §3。
- `agent-runtime`：三个端点（§4）；端口接口 `LlmClient`、`ToolRegistryClient`、`ToolGatewayClient` 定义在 `application/port/`，首期 infra 为**进程内适配**（直接调用对方模块 `application` 包的用例类 `SearchToolsUseCase` / `InvokeToolUseCase`；对方 `api` 包只有 HTTP Controller。抽出接口层与 HTTP 适配为后续 change，v3.2 回写）。`DomainRouter` 规则路由 → Registry 搜索 → `LlmClient`（Spring AI `ChatClient`，OpenAI 兼容，`internalToolExecutionEnabled(false)`，结构化输出）在候选内选工具并产出 `Plan`（`steps[]` 为 3 个工具步骤，`refund.create` 步骤 `requiresConfirmation = true`）→ `ToolSelectionValidator` → Run 状态机 → 低风险自动执行、高风险由 `UiSchemaBuilder` 生成 UI Schema + `confirmationToken` 等待确认 → 确认后重校验再执行。**订单状态重校验经 Gateway 再次调用 `refund.eligibility.check`**。
  - `idempotencyKey = {runId}-{toolId}-{seq}`。
  - `argsDigest = SHA-256(计划固定参数的规范化 JSON)`；确认时 `formData` **只允许**补入当前屏 `Form` 组件 `props.fields[]` 声明的字段名，其余键 → `CONFIRMATION_REJECTED`；合并后参数再经 Gateway `inputSchema` 校验。因此**确认屏必含一个 `Form`**（见 §4.1）。
- `domains/order-service`、`domains/refund-service`：内存数据，实现 6 个 `ToolHandler` + 各自 `ToolManifestSource`（读取 `src/main/resources/tool-manifests/*.json`）。
- `app`：Spring Boot 主类（`@SpringBootApplication(scanBasePackages = "com.strato")`，pom 依赖全部 7 个子模块，`<finalName>app</finalName>`）；`/actuator/health`；`infra/InMemoryPrincipalPermissionResolver`；`application.yml`（权限表等非敏感配置）；`SelfCheckRunner`（遍历 `platform-spi` 的 `SelfCheck` Bean 列表；`strato.selfcheck.enabled` 默认 true，README 说明生产关闭）。自检**直接调用 `ToolHandler`**、不经 Gateway、不产生审计行，且只使用订单 `10003`；`10001` / `10002` 保留给验收链路。
- 环境变量：`STRATO_LLM_BASE_URL`、`STRATO_LLM_API_KEY`、`STRATO_LLM_MODEL`；任一缺失 → `RuleBasedLlmClient` 回退并 WARN。

首期 6 个工具：

| toolId | version | risk.level | confirmation | sideEffect |
|---|---|---|---|---|
| `order.detail.get` | 1.0.0 | low | never | false |
| `order.list.search` | 1.0.0 | low | never | false |
| `refund.eligibility.check` | 1.2.0 | low | never | false |
| `refund.preview` | 1.3.0 | low | never | false |
| `refund.create` | 2.1.0 | high | required | true |
| `refund.status.get` | 1.0.0 | low | never | false |

内存权限表：`user_001@tenant_001` 拥有 `order:read`、`refund:read`、`refund:create`；`user_002@tenant_001` 拥有 `order:read`、`refund:read`。

### 2.3 前端（`fronted/`）
- `shared/api/sseClient.ts`：`fetch` + `ReadableStream` 的 SSE 客户端（POST body、多行 `data`、`\n\n` 分帧、注释帧忽略、`AbortSignal`）。
- `shared/ui/theme/AppThemeProvider.tsx`：antd `ConfigProvider` + antd-mobile CSS 变量，同一套色值，经 `@shared/ui` 导出。
- `shared/ui/device/{DeviceContext.ts,useDevice.ts}`：`'desktop' | 'mobile'` Context 与 Hook，经 `@shared/ui` 导出；`app/providers/DeviceProvider.tsx` 只做一次性判定（视口 < 768 为 mobile）并提供该 Context。
- `shared/ui/generate/`：`types.ts`（**只**定义各组件 `props` 的 Zod，契约中 `props` 为自由 JSON）、`SchemaRenderer`（`import type { UiSchema } from '@entities/agent-run'`，接收已由调用方 Zod 校验的对象；注册表查找层以 `string` 处理 `type`，未知即 `UnknownComponent`）、`componentRegistry`（按 `useDevice()` 选表，`React.lazy` 固定路径）、7 个白名单组件各两套实现，各自渲染时 `parse` 自己的 `props`。白名单：`Form`、`Card`、`Table`、`ResultCard`、`ConfirmationCard`、`OrderCard`、`RefundConfirmCard`。
- `pages/schema-playground`：仅 `env.DEV` 注册路由 `/dev/schema?example={result|confirm|unknown}`；`result` / `confirm` 经别名 `@contracts/examples/*.json` 读取（`tsconfig` paths + Vite alias + `server.fs.allow` 放行 `.harness/contracts`），`unknown` 用本地夹具 `fixtures/unknown.json` 并跳过 Zod 直接传给 `SchemaRenderer`（DEV 专用，注释说明）。
- `entities/agent-run`：Zod 投影（IntentRequest、ActionRequest、UiSchema、SseEvent、ErrorResponse、RunSummary）+ 纯 API `getRun()`。
- `features/agent-chat`：`useAgentRun`、`useSubmitAction`、`AgentChatPanel`。
- `pages/agent`：路由 `/agent`，`pageContext` 从 URL query 读并 Zod 校验；首页加入口。
- `fronted/scripts/check-registry.mjs`：两个注册表键集合相等且等于 `ui-schema.schema.json` 的 `type` enum，纳入 `pnpm -C fronted lint`。
- `fronted/scripts/verify-examples.ts`：用 `vite-node` 执行，对契约示例逐个 `Schema.parse`。

### 2.4 Harness
- 本 change 的 8 阶段产出物。
- 评审 v1 / v2 引出的 Harness 修订已落地：`project-structure.md` §2（platform-spi、依赖矩阵、红线 5 扩展）与红线 3 措辞；`05-styling-spec.md` 主题封装位置；`06-backend-module-spec.md` 模块清单；`backend-standard.md` §7；`contracts.md` §2 增加 `run-summary`；`project-structure.md` §1 增加只读别名 `@contracts/*`；`coding-standard.md` §3 金额单位统一为"元"；`.oxlintrc.json` no-console 例外扩展到 `scripts/**/*.ts`；`check-module-deps.mjs` 范围；`wiki/architecture.md` 事件序列与依赖图；`wiki/api-contracts.md`。

## 3. 非目标（Out of Scope）

- 多 Agent 编排、Workflow 引擎独立部署、补偿事务。
- 真实 IdP / OAuth；首期 `X-Tenant-Id` / `X-User-Id` 请求头 + 内存权限表。
- 持久化数据库；全部内存，重启丢失。
- CI/CD 主动注册、灰度、契约测试自动化；首期由 `ToolManifestSource` 启动注册。
- 领域服务独立部署、HTTP / MCP / RPC 适配；首期进程内 `ToolHandler`；Runtime ↔ Registry / Gateway 也为进程内适配。
- Gateway 熔断与限流；首期只做超时与按 Manifest 重试。
- OpenTelemetry；首期只透传 `traceId` 头并写日志。
- WebSocket、语音。
- 评测平台；首期只在审计日志记录。
- 单元测试与 E2E（仓库层面已决定不纳入门禁）；以 `SelfCheckRunner` 启动自检替代，可通过配置关闭。
- 除 order / refund 之外的领域。
- 前端国际化、深色主题。
- LLM 输出校验失败后的"转人工"通道；首期以 `run.failed{TOOL_SELECTION_INVALID}` 结束（对 `backend-standard.md` §3 的有意简化）。

## 4. 核心场景

### 4.0 事件发射规则（编码依据）

- Run 建立后立即 `run.started`。
- **每次工具调用**（含确认后的重校验）在 `tool.started` 前发一次 `tool.selected`，调用结束发 `tool.completed{status}`。
- 需要确认时：`ui.replace` 紧跟 `confirmation.required`，Run → `WAITING_CONFIRMATION`，SSE 关闭。
- 终态：`run.completed` 或 `run.failed{code}`，之后关闭。
- 每 15 秒注释帧 `: ping`。
- 事件 `data` 不含模型推理原文、工具参数原文、堆栈、凭据。

### 4.1 主链路："帮我把这个订单退款"

前端请求体（即 `examples/intent-request.example.json` 的内容）：

```json
{
  "conversationId": "conv_001",
  "message": "帮我把这个订单退款",
  "pageContext": {
    "page": "order-detail",
    "selectedEntity": { "type": "order", "id": "10001" }
  },
  "clientCapabilities": {
    "uiSchemaVersion": "1.0",
    "components": ["Form", "Card", "Table", "ResultCard", "ConfirmationCard", "OrderCard", "RefundConfirmCard"]
  }
}
```

```
POST /agent/runs                                            [SSE 打开]
  ← run.started
Runtime: DomainRouter 命中 "退款" → refund
  → Registry.search{domain:refund, principal:user_001@tenant_001} → 4 候选
  → LlmClient.plan → Plan.steps = [eligibility.check, preview, create(requiresConfirmation)]
  ← tool.selected / tool.started → Gateway(eligibility.check, low) ← tool.completed(succeeded)
  ← tool.selected / tool.started → Gateway(preview, low)           ← tool.completed(succeeded)
Runtime: 步骤 3 requiresConfirmation → UiSchemaBuilder 生成 OrderCard + RefundConfirmCard(摘要) + Form{fields:[{name:"reason",type:"select",options}]} + action confirm-refund{confirmationToken}
  ← ui.replace
  ← confirmation.required                                    Run=WAITING_CONFIRMATION，SSE 关闭
用户选原因 DAMAGED，点"确认退款"
POST /agent/runs/{runId}/actions/confirm-refund {confirmationToken, formData:{reason:"DAMAGED"}}   [SSE 打开]
Runtime: Token → 计划；校验 runId / actionId / 未用 / 未过期 / argsDigest / formData 键白名单 / 权限
  ← tool.selected / tool.started → Gateway(eligibility.check)      ← tool.completed   （重校验）
  ← tool.selected / tool.started → Gateway(refund.create, {orderId,"amount":"128.00",reason}, idempotencyKey) ← tool.completed
  ← ui.replace(ResultCard)
  ← run.completed                                            Run=COMPLETED
```

### 4.2 拒绝路径与错误码

SSE `run.failed.data.code`：

| code | 触发 |
|---|---|
| `CONFIRMATION_REJECTED` | Token 过期 / 已用 / argsDigest 不符 / formData 含白名单外键 / 权限不足 / 重校验不通过；`refund.create` 未调用 |
| `TOOL_SELECTION_INVALID` | LLM 输出 toolId 不在候选内，重试一次仍失败 |
| `TOOL_OUTPUT_INVALID` | Gateway 输出 Schema 校验失败（先发 `tool.completed{failed}`） |
| `TOOL_EXECUTION_FAILED` | Gateway 返回 `tool-invoke.response{status=failed}`（error.code ∈ INPUT_INVALID / FORBIDDEN / TOOL_NOT_FOUND / TIMEOUT / HANDLER_ERROR）或传输失败（先发 `tool.completed{failed}`）；确认路径上的 FORBIDDEN 映射为 `CONFIRMATION_REJECTED` |
| `INTERNAL_ERROR` | 未分类异常 |

领域路由无命中 → `message.delta("当前没有可用能力处理该请求")` + `run.completed`。

HTTP `error-response.code`：

| status | code | 场景 |
|---|---|---|
| 400 | `REQUEST_INVALID` | 请求体或工具参数 Schema 校验失败 |
| 401 | `UNAUTHENTICATED` | 缺 `X-Tenant-Id` 或 `X-User-Id` |
| 403 | `FORBIDDEN` | permission 不满足 |
| 404 | `NOT_FOUND` | runId / toolId 不存在 |
| 409 | `TOOL_VERSION_CONFLICT` | 重复注册同 `toolId@version` |
| 500 | `INTERNAL_ERROR` | 未分类异常 |
| 502 | `INTERNAL_ERROR` | Gateway HTTP 端点：下游工具 TIMEOUT / HANDLER_ERROR / OUTPUT_INVALID（进程内适配不经 HTTP，直接得到 failed Response） |

> v3.2（阶段 4 回写）：Runtime 端口 `ToolGatewayClient` 的进程内适配把 `GatewayException` 转为契约 `response.failed` 形态，Runtime 按 `error.code` 结构化映射；所有入站请求体先按契约 Schema 校验再绑定 record；确认路径按 runId 互斥，令牌未消费前的拒绝不改变 Run 状态；确认后金额只取重校验结果并须等于确认屏展示值。

### 4.3 注册路径
- app 启动 → Registry 在 `ApplicationReadyEvent` 拉取两个 `ToolManifestSource` 共 6 个 Manifest 注册；随后经 HTTP 再 `POST` `refund.create@2.1.0` → 409。

## 5. 契约影响

新增 `.harness/contracts/`（根结构必须能直接校验示例，见 `00-contract-spec.md`）：

| 文件 | 方向 | 根结构 | 示例文件 |
|---|---|---|---|
| `error-response.schema.json` | 通用 | object `{code(enum 见 §4.2), message, traceId, details?[]}` | `error-response.example.json` |
| `intent-request.schema.json` | 前端 → Runtime | object | `intent-request.example.json`（§4.1 原文） |
| `action-request.schema.json` | 前端 → Runtime | object `{confirmationToken, formData}` | `action-request.example.json` |
| `ui-schema.schema.json` | Runtime → 前端 | object；`components[].type` enum 恰为 7 名；`actions[].style ∈ {default, primary, danger}`；无 URL / HTML | `ui-schema.example.json`（确认屏）、`ui-schema.result.example.json` |
| `run-summary.schema.json` | Runtime → 前端 | object `{runId, conversationId, state(enum 6), currentUi?($ref ui-schema), failureCode?, createdAt, updatedAt}` | `run-summary.example.json` |
| `sse-events.schema.json` | Runtime → 前端 | `oneOf` 10 事件 `{event, data}`；`ui.replace` / `ui.patch` 的 data `$ref` ui-schema；`run.failed.data.code` enum 见 §4.2（5 值） | `sse-events.{run-started,message-delta,tool-selected,tool-started,tool-completed,ui-replace,ui-patch,confirmation-required,run-completed,run-failed}.example.json` 共 10 |
| `tool-manifest.schema.json` | 领域服务 → Registry | object；`description.maxLength = 500`；`if high then required`；`if sideEffect then idempotency required` | `tool-manifest.example.json`、`tool-manifest.refund-create.example.json` |
| `tool-search.schema.json` | Runtime ↔ Registry | object `{request, response}`；`response.tools[]` 项 `additionalProperties: false`，恰六字段 | `tool-search.example.json` |
| `tool-invoke.schema.json` | Runtime ↔ Gateway | object `{request, response}` | `tool-invoke.example.json` |

合计 9 schema、20 example。

## 6. 验收标准

约定：`export DEPLOY=.harness/changes/feat-agent-tool-platform-20260903/deployment`；后端以 `java -jar backed/app/target/app.jar > $DEPLOY/backend.log 2>&1 &` 启动；日志类断言均对 `$DEPLOY/backend.log`，截图与事件日志亦落 `$DEPLOY/`。

### 6.1 契约
- [ ] `pnpm -C .harness run check-contracts` 退出码 0，输出 `9 schemas OK`。
- [ ] 临时反例 `risk.level=high, confirmation=never` 经 ajv 被拒；临时反例 tool-search response 项多 `baseUrl` 被拒（命令与输出记入 coding_report）。
- [ ] `grep -c "http" .harness/contracts/examples/ui-schema*.json` 为 0。

### 6.2 后端
- [ ] `node .harness/scripts/mvn.mjs -q -B verify` 退出码 0。
- [ ] `pnpm -C .harness run check-module-deps` 退出码 0。
- [ ] `curl -s localhost:8080/actuator/health` 含 `"status":"UP"`。
- [ ] `grep -c "selfcheck: contracts 9 schemas, 20 examples OK" $DEPLOY/backend.log` 为 1。
- [ ] （v3.2）`POST /agent/runs` 带契约外字段 / `uiSchemaVersion` 非 `1.0` / `formData` 含对象值 / 非法 JSON → 400 `REQUEST_INVALID`；`POST /internal/tool-gateway/invoke` 的 `runId` 不符 pattern → 400。
- [ ] （v3.2）同一 Token 并发两次确认：恰一条流 `run.completed`、另一条 `run.failed{CONFIRMATION_REJECTED}`，`GET /agent/runs/{runId}` 为 `COMPLETED`，该订单退款数 1。
- [ ] （v3.2）结果屏「退款金额」== 确认屏 `RefundConfirmCard.props.amount`。
- [ ] `POST /internal/tool-registry/search`（`user_001`，domain refund）响应通过 `tool-search.response` 校验且 `tools.length == 4`；`user_002` 同请求 `tools.length == 3`。
- [ ] 经 HTTP 重复注册 `refund.create@2.1.0` → 409，body `code == "TOOL_VERSION_CONFLICT"`。
- [ ] `POST /internal/tool-gateway/invoke` 缺 `arguments.orderId` → 400 `REQUEST_INVALID`；`user_002` 调 `refund.create` → 403 `FORBIDDEN`。
- [ ] 缺 `X-Tenant-Id` 调 `POST /agent/runs` → 401 `UNAUTHENTICATED`。
- [ ] 未配置 LLM 环境变量时，`curl -N -X POST /agent/runs --data @intent-request.example.json` 5 秒内结束，`grep '^event:'` 依次为：`run.started`、`tool.selected`、`tool.started`、`tool.completed`、`tool.selected`、`tool.started`、`tool.completed`、`ui.replace`、`confirmation.required`。
- [ ] 用 `confirmationToken` 调 `POST /agent/runs/{runId}/actions/confirm-refund`，事件依次为：`tool.selected`、`tool.started`、`tool.completed`、`tool.selected`、`tool.started`、`tool.completed`、`ui.replace`、`run.completed`。
- [ ] `GET /agent/runs/{runId}` 响应通过 `run-summary.schema.json` 校验且 `state == "COMPLETED"`。
- [ ] 同一 Token 第二次提交 → `run.failed`，`data.code == "CONFIRMATION_REJECTED"`；`grep -c "toolId=refund.create .*status=succeeded" $DEPLOY/backend.log` 为 1。
- [ ] 经 Gateway 调 `refund.status.get{orderId:"10001"}` 返回 `refunds.length == 1`。
- [ ] 审计行含 9 字段：`runId=`、`toolCallId=`、`toolId=`、`version=`、`principal=`、`argsDigest=`、`status=`、`durationMs=`、`traceId=`。
- [ ] `grep -c "帮我把这个订单退款" $DEPLOY/backend.log` 为 0。
- [ ] `grep -rn "alibaba\|langchain4j" backed/ --include=pom.xml` 无输出。
- [ ] `pnpm -C .harness run check-module-deps` 对 DDD 分层 `domain/` 包（排除领域模块基础包 `com.strato.domain.<svc>`）全文匹配 `org.springframework.` / `com.fasterxml.` 无违规；`grep -rln "org.springframework" backed/platform-spi/src` 无输出。

### 6.3 前端
- [ ] `pnpm -C fronted run ci` 退出码 0（含 `check-registry`）。
- [ ] `grep -rn "from 'antd\|from 'antd-mobile\|from '@ant-design" fronted/src --include=*.tsx --include=*.ts | grep -v "src/shared/ui/"` 无输出。
- [ ] `pnpm -C fronted exec vite-node scripts/verify-examples.ts` 输出 `16 examples OK`（intent / action / ui-schema×2 / run-summary / sse-events×10 / error）。
- [ ] 人工验收脚本（截图存 `$DEPLOY/`）：
  1. 后端已启动，`pnpm -C fronted dev`。
  2. 视口 1280：`/dev/schema?example=confirm`，`document.querySelectorAll('[class^=ant-]').length > 0` 且 `[class^=adm-]` 为 0。截图 `ui-playground-desktop.png`。
  3. 视口 375：同 URL，`[class^=adm-]` > 0 且 `[class^=ant-]` 为 0。截图 `ui-playground-mobile.png`。
  4. `/dev/schema?example=unknown`，出现 `UnknownComponent` 占位，console.error 含 `unknown component type` 且无其他 error（开发模式 StrictMode 会双渲染，不对次数做精确计数）。截图 `ui-unknown.png`。
  5. 视口 1280：`/agent?page=order-detail&entityType=order&entityId=10001`，输入"帮我把这个订单退款"回车，依次出现两条工具进度、确认卡片；选原因、点"确认退款"，出现结果卡片；console 无 error。截图 `ui-desktop-flow.png`。

### 6.4 全仓
- [ ] `pnpm -C .harness run ci` 退出码 0。
- [ ] `pnpm -C .harness run doctor` 0 errors。

## 7. 风险与权衡

| 风险 | 影响 | 缓解 |
|---|---|---|
| LLM 输出不受控 | 越权执行 | 候选白名单 + 参数 Schema 校验 + 高风险强制确认；description 转义限长 500；无 key 时规则实现 |
| `confirmationToken` 重放 / 篡改 / formData 注入 | 重复或篡改退款 | 一次性 + 过期 + argsDigest + formData 键白名单 + Gateway 重校验 + 幂等键 |
| 职责渗透（单进程易直连） | 控制面膨胀、红线失守 | `platform-spi` 隔离；`check-module-deps` 校验 runtime / registry / gateway / spi / contracts-java |
| `ToolHandler` / `ToolManifestSource` 归属错误 | Maven 循环依赖 | 全部接口在 `platform-spi`，依赖方向写入 project-structure §2 |
| antd + antd-mobile 双引入 | bundle 膨胀 | 按端型 `React.lazy` 固定路径；deploy-verify 记录 baseline |
| 单进程掩盖跨服务问题 | 上线后行为差异 | 端口接口签名与未来 HTTP 适配一致；超时 / 重试只在 Gateway |
| SSE POST 自实现解析 | 断流 / 半包 | 分帧 + 多行 data + 15s ping |
| JDK 8 默认与 Java 21 冲突 | 构建失败 | `mvn.mjs` 强制 JAVA_HOME；enforcer ≥ 21 |
| 无测试 | 回归靠人工 | 验收全部命令化；`SelfCheckRunner` 启动自检；UI 有固定脚本 |

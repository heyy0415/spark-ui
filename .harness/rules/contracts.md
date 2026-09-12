# Rule: 契约优先（Contracts First）

> 前后端之间、Agent 与工具之间的一切数据结构，真源只有一处：`.harness/contracts/`。两端实现都是它的投影。

## 1. 真源与投影

| 层 | 位置 | 角色 |
|---|---|---|
| 契约 | `.harness/contracts/*.schema.json`（JSON Schema 2020-12） | **真源** |
| 示例 | `.harness/contracts/examples/*.json` | 每个 Schema ≥ 1 个合法示例，CI 校验 |
| 前端 | 全在 `spark-ui/packages/core`：ui-schema 在 `src/schema/uiSchema.ts`，intent-request / action-request / sse-events / run-summary / error-response 在 `src/client/contracts.ts`（Zod）。tool-manifest / tool-search / tool-invoke 是后端内部契约，**无前端投影** | 投影，字段与约束必须与 Schema 一致 |
| 后端 | `spark-rooter/spark-rooter-contracts/`（record + 校验器；`src/main/resources/contracts/` 是真源的机械副本，由 `pnpm -C .harness run sync-contracts` 生成、`check-contracts` 校验一致，勿手改） | 投影 |

变更顺序固定：**先改 Schema 与示例 → 跑 `check-contracts` → 再改两端**。

## 2. 首期契约清单

| 文件 | 内容 |
|---|---|
| `intent-request.schema.json` | 前端 → Runtime：`conversationId`、`message`、`clientCapabilities`（只有自然语言，无页面上下文 / 身份 / 业务字段） |
| `action-request.schema.json` | 前端 → Runtime：`confirmationToken`、`formData` |
| `ui-schema.schema.json` | Runtime → 前端：`schemaVersion`、`screenId`、`components[]`、`actions[]` |
| `sse-events.schema.json` | Runtime → 前端事件：`run.started`、`message.delta`、`tool.selected`、`tool.started`、`tool.completed`、`ui.replace`、`ui.patch`、`confirmation.required`、`run.completed`、`run.failed` |
| `tool-manifest.schema.json` | 领域服务 → Registry：`toolId`、`version`、`domain`、`inputSchema`、`outputSchema`、`risk`、`authorization`、`execution`、`owner`、`status` |
| `tool-search.schema.json` | Runtime → Registry：请求与响应 |
| `tool-invoke.schema.json` | Runtime → Gateway：请求与响应（`executionContext = {runId, toolCallId, sessionId, idempotencyKey, traceId?}`，无 userId / tenantId） |
| `error-response.schema.json` | 通用错误：`code`、`message`、`traceId` |
| `run-summary.schema.json` | Runtime → 前端：`GET /agent/runs/{runId}` 响应，`runId`、`state`、`currentUi?`、时间戳 |

## 3. 通用约束（所有 Schema 共用）

- `id`、`*Id`、金额（`amount`）、Token 一律 `"type": "string"`；金额附 `"pattern": "^-?\\d+(\\.\\d{1,2})?$"`。
- 时间字段 `"format": "date-time"`。
- 对外结构 `"additionalProperties": false`；自由 JSON 字段（`arguments`、`props`、`formData`）显式标注 `"type": "object"` 且不加 `additionalProperties: false`。
- 每个 Schema 有 `$id`、`title`、`description`。
- 每个 Schema 顶层带 `schemaVersion` 或在 `$id` 中带版本；破坏性变更发新版本文件，不改旧文件。

## 4. UI Schema 专项

- `components[].type` 必须落在前端注册表白名单内；白名单清单同步维护在 `ui-schema.schema.json` 的 `enum` 中。新增组件 = 改 Schema enum + 前端注册表 + 后端生成逻辑，三处同一 change。
- **白名单只收 antd / antd-mobile 官方组件的直接映射**（当前 5 个：`Form / Card / Table / Result / Timeline`），`type` 名即官方组件名；禁止业务命名组件（`OrderCard` 之类）。新增 type 前必须先回答「能否用现有组件的 props 表达」，只有「不能」才允许新增。五个组件的 props 全部在 Schema 内以 `if/then` 约束，前端 Zod `.strict()` 同源。
- 变更记录：feat-commerce-domains-20260908 v3.2 把首期 4 个业务 type（`ResultCard / ConfirmationCard / OrderCard / RefundConfirmCard`）与 v3.1 短暂加入的 3 个（`OrderList / ProductList / LogisticsTimeline`）一并删除并收敛为上述 5 个；当时无外部消费方，`schemaVersion` 仍 `1.0`。
- `actions[].confirmationToken` 为不透明字符串，前端只回传，不解析。
- UI Schema 中**不得**出现 URL、脚本、HTML 字符串字段。
- 列表组件内的 `actions[].intent`（`inlineAction`）是一段**自然语言文本**：前端点击后把它原样作为新的用户消息发送，走完整的路由 / 候选过滤 / 校验 / 确认链路；不是命令、不是 URL、不带 token。Schema 以 pattern 禁止 `://` 与 `<`。
- `Card.actions[]`（refactor-spark-embedded-starter-20260909）与 `Table.rows[].actions[]` 同为 `inlineAction`，≤ 6 项；用于详情屏的「返回列表」「查看物流」等二级导航，多级界面全部由后端预写自然语言 `intent` 驱动，前端不拼参数。
- `inlineAction` 的 `label ↔ intent` 语义绑定固定：`查看物流 → 含「物流」`、`申请售后 → 含「售后」`、`删除订单 → 含「删除」`、`退款 → 含「退款」`、`查看商品 → 含「查看商品」`，且 intent 必须含该行实体 ID。后端 `InlineActionSelfCheck` 与契约示例都按此约束。

## 5. Tool Manifest 专项

- `toolId` 形如 `{domain}.{resource}.{verb}`，小写，点分隔。
- `version` 为 semver；已发布版本不可变，Registry 拒绝覆盖。
- `risk.level ∈ {low, medium, high}`；`risk.confirmation ∈ {never, required}`；`high` 必须 `required`。
- `execution.idempotency ∈ {none, required}`；`risk.sideEffect = true` 时必须 `required`。
- `description` 视为不可信文本，Runtime 注入 prompt 前需转义与长度限制（≤ 500 字符）。
- `authorization.permission` 可选（refactor-spark-embedded-starter-20260909）：内核不做用户鉴权，`@SparkTool` 推导的 Manifest 输出 `authorization: {}`；宿主要做权限用自己的方法级 AOP 或 `ToolAccessPolicy`。

## 5b. Provider 段与 protocol 语义（feat-provider-http-transport-20260912）

- `protocol` 决定寻址方式：`in-process` = 领域服务与内核同进程（单体内嵌）；`http` = 领域服务为独立进程（微服务）；`mcp` **保留未实现**，Registry 注册时拒绝。
- `provider` 段仅 `protocol=http` 时出现，两条 `if/then` 硬约束：`http ⇒ provider` 必填；`in-process ⇒ provider` **不得出现**（单体形态无远程坐标概念，出现即配置错误）。
- `provider.serviceName` 与调用方认证密钥绑定——只校验「令牌有效」不够，那样服务 A 的密钥就能注册服务 B 的工具。
- `provider.baseUrl` 可选：接注册中心的宿主只给 `serviceName`，由宿主实现的 `ProviderEndpointResolver` 解析。两者都缺 → 注册失败。允许 `http://`（内网部署与本地联调），但 hub 启动会 WARN，生产应用 HTTPS 或 mTLS。
- `provider.instanceId` 仅用于审计与排障，**不参与寻址**。
- **非破坏性变更**：`provider` 是新增字段且在 `in-process` 下禁止出现，既有单体 Manifest 一字不改仍合法。`$id` 保持 `/v1/`、`schemaVersion` 保持 `1.0`。
- 前端**不投影** `tool-manifest`：它是领域服务 → Registry 的后端内部契约。

## 5a. 变更记录：refactor-spark-embedded-starter-20260909

**破坏性变更、原地改 v1 不发新版本文件**：删 `pageContext` / `principal`、替换 `executionContext` 必填字段属破坏性变更；与 §4 的组件收敛同理，当时无任何外部消费方（前端与后端同仓同 change 改），`$id` 仍为 `/v1/`、`schemaVersion` 仍 `1.0`。首个外部消费方出现后再有破坏性变更必须发 `/v2/`。

| 契约 | 变更 | 原因 |
|---|---|---|
| 全部 9 个 | `$id` 前缀由旧项目名域改为 `https://spark-rooter.local/contracts/v1/`；`SchemaValidator.ID_PREFIX` 同步 | 工程改名（T02） |
| `intent-request` | 删 `pageContext`（含 `selectedEntity`） | 前端始终只发自然语言，实体 ID 在 `message` 内；上下文由后端会话记忆补位 |
| `tool-search.request` | 删 `principal`，`required: ["domain"]` | 内核不识别用户；权限过滤交宿主 `ToolAccessPolicy` |
| `tool-invoke.executionContext` | 删 `userId / tenantId`，增 `sessionId` | 令牌 / 审计按宿主 `SessionIdResolver` 的会话键隔离 |
| `tool-manifest.authorization` | `permission` 改可选 | 推导 Manifest 无权限语义 |
| `ui-schema.cardProps` | 增 `actions[]`（`inlineAction`，≤ 6） | 详情屏二级导航 |
| `tool-search.request` | `domain` 改可选（refactor-llm-planner-domain-free） | 内核不做领域路由，模型在全部可发现工具里选 |

## 6. 变更流程

1. 在 change 的 `spec.md` 列出受影响的契约文件。
2. 修改 `.harness/contracts/`，补示例，`pnpm -C .harness run sync-contracts` 同步后端副本，`pnpm -C .harness run check-contracts` 通过（含副本一致性）。
3. 更新 `.harness/wiki/api-contracts.md` 索引。
4. 后端实现与校验；前端 Zod 投影。
5. 评审（execution 模式）逐项比对 Schema 与两端实现。

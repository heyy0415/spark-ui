# agent-runtime

**决策面 + 状态面**。理解意图、路由领域、发现工具、规划、编排执行、管理 Run 生命周期与确认令牌。**不直连领域服务**：所有工具调用经 `ToolGatewayClient`（agent-safety §1）。

## 对外端点

| 端点 | 说明 |
|---|---|
| `POST /agent/runs` | `intent-request` → `text/event-stream`（`sse-events`）。头 `X-Tenant-Id` / `X-User-Id` 必填，缺失 401。 |
| `POST /agent/runs/{runId}/actions/{actionId}` | `action-request` → SSE。runId 不存在同步 404。 |
| `GET /agent/runs/{runId}` | `run-summary`。只能查自己的 Run。 |

## 流程

路由（`DomainRouter` 关键词规则）→ `ToolRegistryClient.search`（按 principal 过滤后的候选）→ `LlmClient.plan`（Spring AI，`internalToolExecutionEnabled=false`；无 key 时 `RuleBasedLlmClient`）→ `ToolSelectionValidator`（toolId 必须在候选内、args 键必须在 inputSchema 内）→ 逐步执行：低风险自动经 Gateway；`requiresConfirmation` 步骤生成 UI Schema（`UiSchemaBuilder`，必含 `Form`）+ `confirmationToken` → `WAITING_CONFIRMATION`。确认时：令牌一次性 / 10 分钟 / argsDigest / formData 键白名单 全部校验 → 经 Gateway 重调 `refund.eligibility.check` → 执行目标工具 → ResultCard → `COMPLETED`。

事件按 spec §4.0 发射，发出前经 `sse-events` 契约校验；每 15s `: ping`。

## 环境变量

`STRATO_LLM_BASE_URL`、`STRATO_LLM_API_KEY`、`STRATO_LLM_MODEL`；任一缺失 → 规则规划器并 WARN。

## 包结构

`api`（SSE 控制器、事件 Sink、异常映射、线程池）/ `application`（编排器、令牌服务、UI 生成、端口）/ `domain`（Run 状态机、Plan / Step、路由、令牌；无框架依赖）/ `infra`（内存仓储、LLM 客户端、进程内 Registry / Gateway 适配、自检）。

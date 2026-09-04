# API Contracts（索引）

> 真源是 `.harness/contracts/*.schema.json`，示例在 `.harness/contracts/examples/`。本文只做端点与 Schema 的映射索引。

## 前端 ↔ Agent Runtime

| 端点 | 方法 | 请求 Schema | 响应 |
|---|---|---|---|
| `/agent/runs` | POST | `intent-request` | `text/event-stream`，事件按 `sse-events` |
| `/agent/runs/{runId}/actions/{actionId}` | POST | `action-request` | `text/event-stream`，事件按 `sse-events` |
| `/agent/runs/{runId}` | GET | — | `run-summary`（state、当前 UI Schema） |

请求头：`X-Tenant-Id`、`X-User-Id`（首期简化身份）、可选 `X-Trace-Id`。缺失身份头 → 401 `UNAUTHENTICATED`；他人的 runId → 404（不泄露存在）。

健康检查：`GET /actuator/health`（仅暴露 health）。前端 dev server 只代理 `/agent/runs` 与 `/actuator` 两个前缀，`/agent` 本身是 SPA 路由。

## Agent Runtime ↔ Tool Registry（内部）

| 端点 | 方法 | Schema |
|---|---|---|
| `/internal/tool-registry/tools` | POST | `tool-manifest`（注册；同 id@version 重复 → 409） |
| `/internal/tool-registry/search` | POST | `tool-search`（request / response） |
| `/internal/tool-registry/tools/{toolId}/versions` | GET | Manifest 列表 |

## Agent Runtime ↔ Tool Gateway（内部）

| 端点 | 方法 | Schema |
|---|---|---|
| `/internal/tool-gateway/invoke` | POST | `tool-invoke`（request / response） |

## 领域服务（模拟，进程内 ToolHandler，首期不暴露 HTTP）

| 服务 | 实现的工具 |
|---|---|
| order-service | `order.detail.get`、`order.list.search` |
| refund-service | `refund.eligibility.check`、`refund.preview`、`refund.create`、`refund.status.get` |

领域服务通过 `platform-spi` 的 `ToolHandler` 被 Gateway 调用；独立部署与 HTTP / MCP 适配为后续 change。

## 错误

所有 4xx / 5xx 返回 `error-response`：`{ code, message, traceId }`。

## SSE 事件清单

`run.started`、`message.delta`、`tool.selected`、`tool.started`、`tool.completed`、`ui.replace`、`ui.patch`、`confirmation.required`、`run.completed`、`run.failed`。结构见 `sse-events.schema.json`。

## 契约变更流程

见 `.harness/rules/contracts.md` §6。

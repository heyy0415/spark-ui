# API Contracts（索引）

> 真源是 `.harness/contracts/*.schema.json`，示例在 `.harness/contracts/examples/`。本文只做端点与 Schema 的映射索引。

## 前端 ↔ Agent Runtime

| 端点 | 方法 | 请求 Schema | 响应 |
|---|---|---|---|
| `/agent/runs` | POST | `intent-request` | `text/event-stream`，事件按 `sse-events` |
| `/agent/runs/{runId}/actions/{actionId}` | POST | `action-request` | `text/event-stream`，事件按 `sse-events` |
| `/agent/runs/{runId}` | GET | — | `run-summary`（state、当前 UI Schema） |

请求体只有自然语言 `message` + `conversationId` + `clientCapabilities`，无页面上下文与身份字段。身份与权限完全在宿主工程：宿主实现 `SessionIdResolver` 把自己的登录态映射为 `sessionId`（默认实现回落为 `conversationId`，仅 demo，启动 WARN）；非本 `sessionId` 的 runId → 404（不泄露存在）。可选 `X-Trace-Id`。

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

## 示例领域服务（`examples/domains`，`@SparkTool` 形态，进程内）

| 服务 | 实现的工具 | 屏 / 重校验（spi `ScreenBuilder` / `ConfirmationRecheck`） |
|---|---|---|
| order-service | `order.list.search`、`order.detail.get`、`order.logistics.get`、`order.delete` | `OrderScreens`（Table / Card / Timeline / Result）、`OrderDeleteRecheck` |
| product-service | `product.list.search`、`product.detail.get` | `ProductScreens`（Table / Card） |
| aftersale-service | `aftersale.list.get`、`aftersale.create` | `AftersaleScreens`（Card + Form / Result）、`AftersaleRecheck` |
| refund-service | `refund.eligibility.check`、`refund.preview`、`refund.create`、`refund.status.get` | `RefundScreens`（Card + Card + Form / Result）、`RefundRecheck` |

领域工具是 `@Service` 上的 `@SparkTool` 方法（record In / Out，`@SparkParam` 决定 inputSchema），starter 启动时扫描推导 Manifest 并注册；Gateway 经 Spring 代理反射调用（宿主方法级切面生效）。屏与重校验由 runtime 的 `ScreenRegistry` / `RecheckRegistry` 按 toolId 查表。领域之间不 import（`check-module-deps`），跨领域读订单只经 demo-support 的 `OrderSnapshotProvider`。示例宿主 `examples/host-demo` 另有 `demo.whoami`（回显宿主用户，验证上下文传播）。`/internal/**` 端点默认不装配（`spark.web.internal-endpoints=true` 打开）。

### UI Schema 组件白名单（5，官方组件映射）

`Form` / `Card`（`actions[].intent` 为卡片底部行内指令）/ `Table`（`rows[].actions[].intent` 为行内指令）/ `Result` / `Timeline`；props 全部契约级（`ui-schema.schema.json` if/then）。多级界面（列表 → 详情 → 返回）全部由后端在屏里预写自然语言 `intent`，前端点击后原样作为新消息发送。

## 错误

所有 4xx / 5xx 返回 `error-response`：`{ code, message, traceId }`。

## SSE 事件清单

`run.started`、`message.delta`、`tool.selected`、`tool.started`、`tool.completed`、`ui.replace`、`ui.patch`、`confirmation.required`、`run.completed`、`run.failed`。结构见 `sse-events.schema.json`。

## 契约变更流程

见 `.harness/rules/contracts.md` §6。

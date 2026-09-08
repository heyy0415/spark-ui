# tool-gateway

**执行面**。所有工具调用的唯一出口；Agent Runtime 不得绕过它直连领域服务。不做规划、不选工具。

## 对外端点

| 端点 | 说明 |
|---|---|
| `POST /internal/tool-gateway/invoke` | `tool-invoke` 契约。请求含 `toolId@toolVersion`、`arguments`、`executionContext{runId, toolCallId, userId, tenantId, idempotencyKey, traceId?}`。 |

## 执行顺序（固定）

1. 寻址：`ToolResolver`（Registry 提供）取 Manifest；不存在 → 404 `NOT_FOUND`
2. 输入 Schema 校验：按 Manifest `inputSchema` → 400 `REQUEST_INVALID`
3. 鉴权：`PrincipalPermissionResolver` 必须含 `authorization.permission` → 403 `FORBIDDEN`
4. 幂等：`execution.idempotency = required` 时按 `(tenantId, idempotencyKey)` **先占位后填充**（`IdempotencyStore.claim` → Owner 执行 / Replay 返回首次响应 / Awaiting 等待执行者完成，执行者失败 `release` 后等待方重新 claim）；重放与等待得到的结果审计 `status=replayed`，`succeeded` 恰等于真实执行次数
5. 调用：注入的 `List<ToolHandler>` 中按 `toolId@version` 匹配；超时 `execution.timeoutMs`；重试仅当幂等或无副作用，次数 `execution.maxRetries`
6. 输出 Schema 校验：按 `outputSchema` → 502 `INTERNAL_ERROR`（`OUTPUT_INVALID`）
7. 脱敏：键名 `password / token / secret / apiKey` 的字符串值替换为 `***`
8. 审计：一行 `audit runId= toolCallId= toolId= version= principal= argsDigest= status= durationMs= traceId=`（logger `AUDIT`），只记参数摘要不记原文

## 依赖的端口

`ToolResolver`、`PrincipalPermissionResolver`、`ToolHandler`（全部来自 platform-spi）。pom **不**依赖任何 `domains/*`、`tool-registry` 实现或 `agent-runtime`。

## 未实现（spec §3 非目标）

熔断、限流、HTTP / MCP 协议适配。

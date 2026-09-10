# spark-rooter-gateway

**执行面**。所有工具调用的唯一出口；Runtime 不得绕过它。不做规划、不选工具、**不做用户鉴权**（归宿主）。Bean 由 starter `GatewayBeans` 装配；HTTP 端点在 `web-mvc`（默认不装配）。

## 执行顺序（固定）

1. 寻址：`ToolResolver`（Registry 提供）取 Manifest；不存在 → `TOOL_NOT_FOUND`
2. 输入 Schema 校验：按 Manifest `inputSchema` → `INPUT_INVALID`
3. 宿主访问策略（可选）：`ToolAccessPolicy.allowed(toolId, sessionId)` 为 false → `FORBIDDEN`；默认全放行
4. 幂等：`execution.idempotency = required` 时按 `(sessionId, idempotencyKey)` 先占位后填充（`IdempotencyStore.claim` → Owner / Replay / Awaiting）；重放审计 `status=replayed`
5. 调用：`toolId@version` 匹配 `ToolHandler`（`@SparkTool` 的 `AnnotatedToolHandler` 持**代理** Bean，宿主方法级切面生效）；工具线程先 `RunContextPropagator.restore` 后 `clear`；超时 / 重试按 Manifest
6. 输出 Schema 校验 → `OUTPUT_INVALID`
7. 脱敏：键名 `password / token / secret / apiKey` 的字符串值替换为 `***`
8. 审计：`AuditSink.record`（默认 `LogAuditSink` 一行 `audit runId= toolCallId= toolId= version= sessionId= argsDigest= status= durationMs= traceId=`），只记参数摘要

## 依赖的端口

`ToolResolver`、`ToolHandler`、`AuditSink`、`ToolAccessPolicy`（可选）、`RunContextPropagator`（全部来自 spark-rooter-spi）。pom **不**依赖任何 `examples/domains/*`、registry 实现或 runtime，不依赖 starter-web。

## 未实现（spec 非目标）

熔断、限流、HTTP / MCP 远程传输（`ToolTransport` 只预留接口）。

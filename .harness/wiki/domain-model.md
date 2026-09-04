# Domain Model

> 真源：`.harness/contracts/*.schema.json`。本文档是可读视图。ID、金额、Token 一律 string。

## Tool（工具）

| 字段 | 类型 | 约束 |
|---|---|---|
| toolId | string | `{domain}.{resource}.{verb}`，小写点分 |
| version | string | semver；已发布不可变 |
| domain | string | 所属领域，如 `refund` |
| name / description | string | description ≤ 500 字符，视为不可信文本 |
| inputSchema / outputSchema | JSON Schema | Gateway 双向校验 |
| risk.level | `low \| medium \| high` | high ⇒ confirmation = required |
| risk.sideEffect / reversible | boolean | sideEffect ⇒ execution.idempotency = required |
| risk.confirmation | `never \| required` | |
| authorization.permission | string | 如 `refund:create` |
| execution.timeoutMs / maxRetries / idempotency | number / number / `none \| required` | |
| owner.team | string | |
| status | `draft \| canary \| active \| deprecated` | 只有 active / canary 可被发现 |

## Run（一次 Agent 任务）

| 字段 | 类型 | 约束 |
|---|---|---|
| runId | string | 前缀 `run_` |
| conversationId | string | |
| principal | { userId, tenantId } | 来自请求头，不信任 body |
| state | `CREATED \| PLANNING \| EXECUTING \| WAITING_CONFIRMATION \| COMPLETED \| FAILED` | 迁移幂等 |
| plan | Step[] | 只存后端 |
| createdAt / updatedAt | string | ISO-8601 |

## Step / ToolCall

| 字段 | 类型 |
|---|---|
| toolCallId | string，前缀 `tc_` |
| toolId@version | string |
| arguments | object（按 inputSchema） |
| status | `pending \| running \| succeeded \| failed \| skipped` |
| idempotencyKey | string，`{runId}-{toolId}-{seq}` |

## ConfirmationToken

| 字段 | 约束 |
|---|---|
| token | 不透明字符串，前端只回传 |
| runId / actionId | 绑定 |
| argsDigest | 工具参数摘要，确认时比对 |
| expiresAt | 默认签发后 10 分钟 |
| used | 一次性 |

## UI Schema

`schemaVersion`、`screenId`、`title`、`components[] { id, type(白名单 enum), props }`、`actions[] { id, type(submit|cancel), label, style, confirmationToken? }`。

首期组件白名单：`Form`、`Card`、`Table`、`ResultCard`、`ConfirmationCard`、`OrderCard`、`RefundConfirmCard`。每个 type 有桌面（antd）与移动（antd-mobile）两套实现，props 相同。

## 首期领域与工具

| 领域 | 工具 | 风险 | 确认 |
|---|---|---|---|
| order | `order.detail.get` | low | never |
| order | `order.list.search` | low | never |
| refund | `refund.eligibility.check` | low | never |
| refund | `refund.preview` | low | never |
| refund | `refund.create` | high | required |
| refund | `refund.status.get` | low | never |

## 隐性约束

- 金额单位为"元"字符串，两位小数，形如 `"128.00"`；与前端展示一致，后端运算用 BigDecimal。
- 未知 `risk.level` / `status` 值反序列化必须失败，不降级。
- Registry 查询结果为空不是错误，Runtime 应回复"当前无可用能力"而非猜测。

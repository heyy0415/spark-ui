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
| sessionId | string | 宿主 `SessionIdResolver` 产出的会话隔离键；内核不识别用户（缺该 Bean 拒绝启动，演示实现需显式开关） |
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

组件白名单（5，均为 antd / antd-mobile 官方组件映射）：`Form`、`Card`、`Table`、`Result`、`Timeline`。每个 type 有桌面（antd）与移动（antd-mobile）两套实现，props 相同。

## 领域与工具（四领域 12 工具）

| 领域 | 工具 | 版本 | 风险 | 确认 | 说明 |
|---|---|---|---|---|---|
| order | `order.list.search` | 1.1.0 | low | never | createdAt 倒序，默认 20（≤ 50），排除 DELETED |
| order | `order.detail.get` | 1.1.0 | low | never | 商品行 + 脱敏地址 + 物流概要；DELETED → HANDLER_ERROR |
| order | `order.logistics.get` | 1.0.0 | low | never | 无物流 → `NOT_SHIPPED, events []` |
| order | `order.delete` | 1.0.0 | high | required | 软删；`DeletionPolicy`：仅 COMPLETED / CANCELLED / REFUNDED |
| product | `product.list.search` | 1.0.0 | low | never | keyword（标题 / 描述包含）+ category |
| product | `product.detail.get` | 1.0.0 | low | never | 含 specs / salesCount |
| aftersale | `aftersale.list.get` | 1.0.0 | low | never | 带 orderId 时附 `order` 摘要（确认屏用） |
| aftersale | `aftersale.create` | 1.0.0 | high | required | `AftersalePolicy`：订单 SHIPPED / COMPLETED 且无进行中售后 |
| refund | `refund.eligibility.check` | 1.3.0 | low | never | 输出含订单摘要（状态 / 商品 / 件数 / 金额）供确认屏 |
| refund | `refund.preview` | 1.3.0 | low | never | |
| refund | `refund.create` | 2.1.0 | high | required | `EligibilityPolicy`；金额来自重校验 |
| refund | `refund.status.get` | 1.0.0 | low | never | 不进退款计划 |

### 实体与状态机

- **Order**：`PAID → SHIPPED → COMPLETED`；`PAID → CANCELLED`；`PAID | SHIPPED → REFUNDED`（经退款）；终态 `COMPLETED | CANCELLED | REFUNDED → DELETED`（软删，列表不含、详情报错）。含 `items[]`（Σ 行金额 == 订单金额，加载时校验）、`address{receiver, phoneMasked, region}`、`logistics[]`（seq 连续）。`LogisticsStatus` 派生：无事件 NOT_SHIPPED；COMPLETED 或末条含「签收 / 确认」DELIVERED；末条含「派送」OUT_FOR_DELIVERY；否则 IN_TRANSIT。
- **Product**：全局目录，无状态机；`stock 0` 为缺货。
- **Aftersale**：`SUBMITTED → APPROVED → COMPLETED`；`SUBMITTED → REJECTED`；`SUBMITTED | APPROVED → CANCELLED`。进行中 = {SUBMITTED, APPROVED}，每单 ≤ 1。类型 RETURN / EXCHANGE / REPAIR。
- **Refund**：`SUBMITTED → PROCESSING → COMPLETED | REJECTED`；一单一退。
- 三条写操作策略都是领域 `domain/` 纯函数，被 handler（第二道保险）与 runtime 确认后的 `ConfirmationRecheck`（第一道）共用；跨领域读订单只经 spi `OrderSnapshotProvider`。

### 种子数据

每领域 `src/main/resources/data/{*.json, schema.sql, README.md}`，由 `.harness/scripts/gen-seed.mjs` 生成、`check-seed.mjs` 校验（键 == DDL 列、外键、金额和、状态-物流、夹具表）。商品 20 / 订单 30（10001–10030，10030 最新）/ 售后 4 / 退款 3。

## 隐性约束

- 金额单位为"元"字符串，两位小数，形如 `"128.00"`；与前端展示一致，后端运算用 BigDecimal。
- 未知 `risk.level` / `status` 值反序列化必须失败，不降级。
- Registry 查询结果为空不是错误，Runtime 应回复"当前无可用能力"而非猜测。

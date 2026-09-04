# refund-service（模拟领域服务）

首期内存实现，提供 4 个工具。**不依赖** gateway / registry / runtime，也不依赖 order-service（领域模块互不依赖）：订单快照由本模块 `SeededOrderLookup` 提供，真实系统中替换为 RPC / 事件订阅。

| 工具 | 版本 | 风险 | 权限 |
|---|---|---|---|
| `refund.eligibility.check` | 1.2.0 | low | `refund:read` |
| `refund.preview` | 1.3.0 | low | `refund:read` |
| `refund.create` | 2.1.0 | **high / required / sideEffect** | `refund:create` |
| `refund.status.get` | 1.0.0 | low | `refund:read` |

`refund.create` 按 `(tenantId, idempotencyKey)` 去重；幂等键来自 `ExecutionContext`，由 Runtime 生成、Gateway 透传。

启动自检 `RefundIdempotencySelfCheck` 直接调用 Handler（不经 Gateway、不产生审计行），固定使用订单 10003。

包结构：`domain`（Refund、RefundRepository、EligibilityPolicy、OrderLookup；无框架依赖）/ `application`（RefundService）/ `infra`（内存仓储、订单快照、4 个 Handler、ManifestSource、selfcheck）。

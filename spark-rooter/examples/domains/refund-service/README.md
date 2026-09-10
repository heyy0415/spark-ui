# refund-service（模拟领域服务）

内存实现，提供 4 个工具。**不依赖** gateway / registry / runtime，也不 import order-service（领域模块互不依赖）：订单快照经 `platform-spi` 的 `OrderSnapshotProvider`（`SnapshotOrderLookup` 适配），真实系统中替换为 RPC / 事件订阅。退款单种子来自 `src/main/resources/data/refunds.json`（3 条，挂 10007–10009），DDL 与导入见 `data/`。

| 工具 | 版本 | 风险 | 风险 |
|---|---|---|---|
| `refund.eligibility.check` | 1.3.0 | low | low |
| `refund.preview` | 1.3.0 | low | low |
| `refund.create` | 2.1.0 | **high / required / sideEffect** | `refund:create` |
| `refund.status.get` | 1.0.0 | low | low |

`refund.create` 按 `(tenantId, idempotencyKey)` 去重；幂等键来自 `ExecutionContext`，由 Runtime 生成、Gateway 透传。

启动自检 `RefundIdempotencySelfCheck` 直接调用 Handler（不经 Gateway、不产生审计行），固定使用订单 10003。

包结构：`domain`（Refund、RefundRepository、EligibilityPolicy、OrderLookup；无框架依赖）/ `application`（RefundService）/ `infra`（种子仓储、订单快照适配、4 个 Handler、ManifestSource、selfcheck）。

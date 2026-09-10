# order-service（模拟领域服务）

内存实现，提供 4 个工具（2 只读 + 物流查询 + 高风险删除），通过 `platform-spi` 的 `ToolHandler` 被 Gateway 调用；通过 `ToolManifestSource` 被 Registry 发现。**不依赖** gateway / registry / runtime。

| 工具 | 版本 | 权限 |
|---|---|---|
| `order.detail.get` | 1.1.0 | `order:read` |
| `order.list.search` | 1.1.0 | `order:read` |
| `order.logistics.get` | 1.0.0 | `order:read` |
| `order.delete` | 1.0.0 | `order:delete`（high / required / sideEffect；`DeletionPolicy` 只允许 COMPLETED / CANCELLED / REFUNDED，软删）|

数据：`src/main/resources/data/{orders,order_items,logistics_events}.json`（30 单 / 49 行 / 85 事件，`gen-seed.mjs` 生成，`check-seed.mjs` 校验），夹具：10001 PAID 128.00、10002 SHIPPED 299.00、10003 PAID 1.00（自检专用）、10004 PAID 59.00（幂等 e2e）、10030 最新。DDL 与导入见 `data/`。本模块实现 spi `OrderSnapshotProvider` 供 refund / aftersale 策略层读订单快照。

包结构：`domain`（Order、OrderRepository；无框架依赖）/ `infra`（种子仓储、四个 Handler、OrderJson 投影、OrderSnapshotAdapter、ManifestSource）。Manifest 在 `src/main/resources/tool-manifests/`。

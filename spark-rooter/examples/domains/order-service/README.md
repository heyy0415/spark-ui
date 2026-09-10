# order-service（模拟领域服务）

内存实现，提供 4 个工具（2 只读 + 物流查询 + 高风险删除），以 `@SparkTool` 方法形态声明（`infra/*Tools.java`，record In / Out，`@SparkParam` 决定 inputSchema），starter 启动时扫描推导 Manifest 并注册；Gateway 经 Spring 代理调用。租户 / 用户来自示例宿主的 `DemoUserContext`（真实宿主换成自己的登录态）。**不依赖** gateway / registry / runtime。

| 工具 | 版本 | 风险 |
|---|---|---|
| `order.detail.get` | 1.1.0 | low |
| `order.list.search` | 1.1.0 | low |
| `order.logistics.get` | 1.0.0 | low |
| `order.delete` | 1.0.0 | high（`@SparkRisk` / required / sideEffect；`DeletionPolicy` 只允许 COMPLETED / CANCELLED / REFUNDED，软删）|

数据：`src/main/resources/data/{orders,order_items,logistics_events}.json`（30 单 / 49 行 / 85 事件，`gen-seed.mjs` 生成，`check-seed.mjs` 校验），夹具：10001 PAID 128.00、10002 SHIPPED 299.00、10003 PAID 1.00（自检专用）、10004 PAID 59.00（幂等 e2e）、10030 最新。DDL 与导入见 `data/`。本模块实现 demo-support `OrderSnapshotProvider` 供 refund / aftersale 策略层读订单快照。

包结构：`domain`（Order、OrderRepository；无框架依赖）/ `infra`（种子仓储、`OrderTools`（4 个 @SparkTool）、OrderSnapshotAdapter、ManifestSource）。旧手写 Manifest 已移到 `examples/host-demo/src/main/resources/legacy-manifests/`，供 `ManifestParitySelfCheck` 与推导结果逐字段 diff。

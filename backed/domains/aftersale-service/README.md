# aftersale-service（模拟领域服务）

售后领域服务，提供 2 个工具。**不依赖** gateway / registry / runtime，也不 import 其他领域模块：订单只读经 `platform-spi` 的 `OrderSnapshotProvider`（由 order-service 实现、app 装配）。

| 工具 | 版本 | 风险 | 权限 |
|---|---|---|---|
| `aftersale.list.get` | 1.0.0 | low | `aftersale:read` |
| `aftersale.create` | 1.0.0 | **high / required / sideEffect** | `aftersale:create` |

策略 `AftersalePolicy`：订单 `status ∈ {SHIPPED, COMPLETED}` 且无进行中（SUBMITTED / APPROVED）售后单。`aftersale.list.get` 带 `orderId` 时附带 `order` 摘要，供 runtime 的售后确认屏渲染 OrderCard（屏层不直读订单域）。

数据：`src/main/resources/data/aftersales.json`（4 条：APPROVED 挂 10010，其余非进行中挂 10007–10009），DDL 与导入见 `data/`。

包结构：`domain`（Aftersale、AftersaleRepository、AftersalePolicy；无框架依赖）/ `application`（AftersaleService）/ `infra`（种子仓储、两个 Handler、AftersaleJson 投影、ManifestSource）。

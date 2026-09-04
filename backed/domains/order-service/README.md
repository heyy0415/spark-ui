# order-service（模拟领域服务）

首期内存实现，提供 2 个只读工具，通过 `platform-spi` 的 `ToolHandler` 被 Gateway 调用；通过 `ToolManifestSource` 被 Registry 发现。**不依赖** gateway / registry / runtime。

| 工具 | 版本 | 权限 |
|---|---|---|
| `order.detail.get` | 1.0.0 | `order:read` |
| `order.list.search` | 1.0.0 | `order:read` |

预置数据（tenant_001）：10001 PAID 128.00、10002 SHIPPED 299.00、10003 PAID 1.00（自检专用）。

包结构：`domain`（Order、OrderRepository；无框架依赖）/ `infra`（内存仓储、两个 Handler、ManifestSource）。Manifest 在 `src/main/resources/tool-manifests/`。

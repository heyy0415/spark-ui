# product-service（模拟领域服务）

商品目录领域服务，提供 2 个只读工具，通过 `platform-spi` 的 `ToolHandler` 被 Gateway 调用；通过 `ToolManifestSource` 被 Registry 发现。**不依赖** gateway / registry / runtime，也不 import 其他领域模块。

| 工具 | 版本 | 权限 |
|---|---|---|
| `product.list.search` | 1.0.0 | `product:read` |
| `product.detail.get` | 1.0.0 | `product:read` |

数据：`src/main/resources/data/products.json`（20 件，4 类，含库存 0），由 `.harness/scripts/gen-seed.mjs` 生成；`schema.sql` 为对应 MySQL DDL，导入方式见 `data/README.md`。

包结构：`domain`（Product、ProductRepository；无框架依赖）/ `infra`（种子仓储、两个 Handler、ProductJson 投影、ManifestSource）。

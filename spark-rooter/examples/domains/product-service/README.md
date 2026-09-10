# product-service（模拟领域服务）

商品目录领域服务，提供 2 个只读工具，以 `@SparkTool` 方法形态声明（`infra/*Tools.java`，record In / Out，`@SparkParam` 决定 inputSchema），starter 启动时扫描推导 Manifest 并注册；Gateway 经 Spring 代理调用。租户 / 用户来自示例宿主的 `DemoUserContext`（真实宿主换成自己的登录态）。**不依赖** gateway / registry / runtime，也不 import 其他领域模块。

| 工具 | 版本 | 风险 |
|---|---|---|
| `product.list.search` | 1.0.0 | low |
| `product.detail.get` | 1.0.0 | low |

数据：`src/main/resources/data/products.json`（20 件，4 类，含库存 0），由 `.harness/scripts/gen-seed.mjs` 生成；`schema.sql` 为对应 MySQL DDL，导入方式见 `data/README.md`。

包结构：`domain`（Product、ProductRepository；无框架依赖）/ `infra`（种子仓储、两个 Handler、ProductJson 投影、ManifestSource）。

# spark-rooter-registry

**控制面**。负责工具的注册、发现、版本查询。**不经过业务流量**，没有任何转发或代理端点。Bean 由 starter `RegistryBeans` 装配；HTTP 端点在 `web-mvc`（默认不装配）。

## 来源

- `@SparkTool`：starter 的 `SparkToolScanner` 启动时推导 Manifest 并调 `RegisterToolUseCase`（经 `tool-manifest` 契约校验；`authorization` 为空对象）。
- 手写 `ToolManifestSource`（迁移期仍可用）：`StartupManifestRegistrar` 在 `ApplicationReadyEvent` 注册。两种来源同 `toolId@version` → 启动失败。

## 发现

`ToolSearchPort.search(request)` / `domains()` **不带身份**：只按 `status ∈ {active, canary}` 过滤；宿主定义了 `ToolAccessPolicy` Bean 时再按它过滤。响应项恰六字段（`toolId / version / description / inputSchema / riskLevel / confirmation`）。

## 提供的端口

`ToolResolver`（spi）：供 Gateway 按 `toolId@version` 取回 Manifest 做寻址与 Schema 校验。

## 包结构

`api`（`ToolSearchPort`）/ `application`（注册 / 发现用例）/ `domain`（仓储端口、发现策略、领域异常；无 Spring 依赖）/ `infra`（内存仓储、ToolResolver 实现、启动注册器）。

# tool-registry

**控制面**。负责工具的注册、发现、版本查询与治理元数据。**不经过业务流量**，没有任何转发或代理端点，也没有出向 HTTP 客户端。

## 对外端点

| 端点 | 说明 |
|---|---|
| `POST /internal/tool-registry/tools` | 注册一个 Manifest（`tool-manifest` 契约）。同 `toolId@version` 重复 → 409 `TOOL_VERSION_CONFLICT`。 |
| `POST /internal/tool-registry/search` | 按 domain + principal 发现工具（`tool-search` 契约）。只返回 `status ∈ {active, canary}` 且调用方持有 `authorization.permission` 的工具；响应项恰六字段。 |
| `GET /internal/tool-registry/tools/{toolId}/versions` | 列出某工具的全部已注册版本。 |

## 依赖的端口

- `PrincipalPermissionResolver`（platform-spi）：查询调用方权限，由 `app` 提供内存实现。
- `ToolManifestSource`（platform-spi）：启动时拉取各领域模块暴露的 Manifest，逐个注册。

## 提供的端口

- `ToolResolver`（platform-spi）：供 Gateway 按 `toolId@version` 取回 Manifest 做寻址与 Schema 校验。

## 包结构

`api`（Controller、异常映射）/ `application`（用例）/ `domain`（仓储端口、发现策略、领域异常；无 Spring 依赖）/ `infra`（内存仓储、ToolResolver 实现、启动注册器）。

# backed — Strato Agent Tool Platform 后端

Java 21 / Spring Boot 3.5 / Spring AI 1.1 / Maven 多模块，首期单进程装配。

> 一句话：Agent Runtime 负责理解与规划（决策面），Tool Registry 负责发现与治理（控制面），Tool Gateway 负责安全执行（执行面），领域服务负责确定性业务执行。三者只通过 `platform-spi` 的接口相连；Runtime **不直连**领域服务。

## 模块

| 模块 | 职责 | README |
|---|---|---|
| `platform-spi` | 接口层：`ToolHandler`、`ToolManifestSource`、`ToolResolver`、`PrincipalPermissionResolver`、`SelfCheck`；零 Spring 依赖 | — |
| `contracts-java` | 9 个契约的 record DTO + `SchemaValidator`（networknt，契约从 `.harness/contracts/` 构建期复制） | — |
| `tool-registry` | 注册 / 发现 / 版本；无转发端点 | [README](tool-registry/README.md) |
| `tool-gateway` | 校验 → 鉴权 → 幂等 → 调用 → 输出校验 → 脱敏 → 审计 | [README](tool-gateway/README.md) |
| `agent-runtime` | 路由 → 规划 → 编排 → 确认令牌 → SSE | [README](agent-runtime/README.md) |
| `domains/order-service` | 2 个只读工具，内存数据 | [README](domains/order-service/README.md) |
| `domains/refund-service` | 4 个工具，`refund.create` 高风险需确认 | [README](domains/refund-service/README.md) |
| `app` | 唯一装配点：主类、权限表、`SelfCheckRunner` | — |

依赖方向以 `.harness/rules/project-structure.md` §2 为准，`pnpm -C .harness run check-module-deps` 机械校验。

## 构建与运行

```bash
# 构建（统一入口，自动注入 JDK 21）
node .harness/scripts/mvn.mjs -q -B verify

# 运行（默认 8080）
export DEPLOY=.harness/changes/feat-agent-tool-platform-20260903/deployment
JAVA_HOME=~/.jenv/versions/21 java -jar backed/app/target/app.jar > $DEPLOY/backend.log 2>&1 &
curl -s localhost:8080/actuator/health

# 端到端验收（spec §6.2）
bash .harness/scripts/e2e-backend.sh
```

## 环境变量

| 变量 | 说明 |
|---|---|
| `STRATO_LLM_BASE_URL` | OpenAI 兼容接口地址 |
| `STRATO_LLM_API_KEY` | 密钥（禁止写进代码或 yml） |
| `STRATO_LLM_MODEL` | 模型名 |

三者任一缺失 → 启动日志 WARN，规划器回退为 `RuleBasedLlmClient`（确定性模板），整条链路仍可跑通与验收。

## 配置（`app/src/main/resources/application.yml`）

- `strato.selfcheck.enabled`（默认 `true`）：启动自检（契约 / 幂等 / 规划 / 令牌）。**生产建议 `false`**。自检直接调 `ToolHandler`、不经 Gateway、不产生审计行，只使用订单 `10003`。
- `strato.permissions.grants[]`：内存权限表。**不要改回 Map 形式**，Spring Boot 宽松绑定会吞掉键中的 `@` 与值中的 `:`。

## 首期身份

`X-Tenant-Id` / `X-User-Id` 请求头即 principal；缺失 → 401。真实 IdP 为后续 change。

## 端点速查

| 端点 | 模块 |
|---|---|
| `POST /agent/runs`、`POST /agent/runs/{runId}/actions/{actionId}`（SSE）、`GET /agent/runs/{runId}` | agent-runtime |
| `POST /internal/tool-registry/tools`、`POST /internal/tool-registry/search`、`GET /internal/tool-registry/tools/{toolId}/versions` | tool-registry |
| `POST /internal/tool-gateway/invoke` | tool-gateway |
| `GET /actuator/health` | app |

契约索引见 `.harness/wiki/api-contracts.md`；契约真源在 `.harness/contracts/`。

# spark-rooter — Spark Rooter 后端（Spring Boot Starter）

Java 21 / Spring Boot 3.5 / Spring AI 1.1 / Maven 多模块。**形态是一个 Starter 依赖**：任何 Java 服务引入 `com.sparkrooter:spark-rooter-spring-boot-starter`，在 `@Service` 方法上加 `@SparkTool`，即可被 `spark-ui` 用自然语言驱动。

> 一句话：Runtime 负责理解与规划（决策面），Registry 负责发现与治理（控制面），Gateway 负责安全执行（执行面），宿主的 `@SparkTool` 方法负责确定性业务执行。内核**不识别用户 / 权限 / 页面上下文**——这些全在宿主工程。

## 三步接入（见 `examples/host-demo`）

1. `pom.xml` 引入 `spark-rooter-spring-boot-starter`（本仓库先 `./mvnw -q install` 到本地仓）。
2. 任意 `@Service` 的 public 方法加 `@SparkTool(id, version, domain, name, description)`，参数 / 返回值为 record，参数组件用 `@SparkParam` 开放（未标注不进 inputSchema）、`@SparkDefault` 给默认值；有副作用的方法加 `@SparkRisk` + `@SparkPrerequisite`。启动日志 `spark-rooter: N tools registered from M beans`。
3. 接自己的身份：实现 `RunContextPropagator`（把登录态 ThreadLocal 带到 spark 工作线程）；**生产必须**实现 `SessionIdResolver`（会话隔离键绑登录态，默认实现 = conversationId 仅演示，启动 WARN）。权限用**方法级**切面（`@Aspect` / `@PreAuthorize`），Controller 拦截器对 spark 的代理调用无效。

## 模块

| 模块 | 职责 | README |
|---|---|---|
| `spark-rooter-spi` | 注解 `@SparkTool / @SparkRisk / @SparkPrerequisite / @SparkParam / @SparkDefault`；端口 `ToolHandler`、`ScreenBuilder`、`ConfirmationRecheck`、`AuditSink`、`SessionIdResolver`、`ToolAccessPolicy`、`RunContextPropagator`、`ConversationMemory`、`OrderSnapshotProvider`、`SelfCheck`；预留 `ToolTransport` / `ToolProviderDiscovery`；零 Spring | — |
| `spark-rooter-contracts` | 9 个契约的 record DTO + `SchemaValidator`（契约从 `.harness/contracts/` 构建期打进 jar） | — |
| `spark-rooter-runtime` | 路由 → 抽取 → 记忆补位 → 规划 → 编排 → 令牌 → 屏 / 澄清屏 → SSE 事件 | [README](spark-rooter-runtime/README.md) |
| `spark-rooter-registry` | 注册 / 发现 / 版本；无转发端点 | [README](spark-rooter-registry/README.md) |
| `spark-rooter-gateway` | 校验 → 幂等 → 经 Spring 代理调用 → 输出校验 → 脱敏 → 审计 | [README](spark-rooter-gateway/README.md) |
| `spark-rooter-web-mvc` | `/agent/runs` SSE 端点、`/internal/**`（可选）、异常映射；唯一依赖 starter-web 的平台模块 | — |
| `spark-rooter-spring-boot-starter` | `AutoConfiguration.imports`、`spark.*` 属性、全部默认实现 `@ConditionalOnMissingBean`、`@SparkTool` 扫描 / Manifest 推导 / 代理调用适配、启动自检 | — |
| `examples/demo-support` | 示例宿主的 mock 用户上下文 `DemoUserContext`（纯 JDK） | — |
| `examples/domains/*` | 四个示例领域（order / product / aftersale / refund，12 个工具）`@SparkTool` 形态 + `ScreenBuilder` / `ConfirmationRecheck` + 种子数据 | 各目录 README |
| `examples/host-demo` | **独立 Maven 工程**（parent `spring-boot-starter-parent`，不在根 modules）：引入 starter + 示例领域即可运行的验收物；含拦截器、传播器、方法级权限切面正反例 | [README](examples/host-demo/README.md) |

依赖方向以 `.harness/rules/project-structure.md` §2 为准，`pnpm -C .harness run check-module-deps` 机械校验（平台模块禁 starter-web、禁 Spring 组件注解、禁 `userId / tenantId / Principal`；示例领域不依赖平台模块）。

## 构建与运行

```bash
node .harness/scripts/mvn.mjs -q -B install -DskipTests     # 平台 + 示例领域 → ~/.m2（自动注入 JDK 21）
cd spark-rooter/examples/host-demo && mvn -q -o package -DskipTests
JAVA_HOME=~/.jenv/versions/21 java -jar target/host-demo.jar --server.port=8080
curl -s localhost:8080/actuator/health
bash .harness/scripts/e2e-backend.sh                        # 端到端验收（SPARK_PORT 可改端口）
```

## 配置（`spark.*`，示例见 `examples/host-demo/src/main/resources/application.yml`）

| 属性 | 默认 | 说明 |
|---|---|---|
| `spark.llm.base-url / api-key / model` | 空 | 缺任一 → 规则规划器 + noop 分类器并 WARN；兼容环境变量 `SPARK_LLM_*`（密钥禁止写进代码 / yml / 日志） |
| `spark.runtime.run-pool / ping-pool` | 8 / 2 | 线程池 |
| `spark.runtime.sse-timeout / token-ttl / memory-ttl` | 90s / 10m / 30m | SSE 超时、确认令牌有效期、会话记忆有效期 |
| `spark.gateway.tool-pool` | 8 | 工具执行线程池 |
| `spark.web.base-path` | `/agent` | `/runs` 端点前缀 |
| `spark.web.internal-endpoints` | `false` | 是否装配 `/internal/**` 三个端点 |
| `spark.selfcheck.enabled` | `false` | 启动自检（示例宿主打开；生产建议关） |
| `spark.owner-team` | `host` | 推导 Manifest 的 `owner.team` |

`SPARK_LLM_BASE_URL` 以 `/v{n}` 结尾时只追加 `/chat/completions`，否则按 Spring AI 默认追加 `/v1/chat/completions`。

## 参数与多轮

- **声明层**：只有 `@SparkParam` 组件进 inputSchema；`@SparkDefault` 写进 schema `default`。
- **抽取层**（确定性）：实体 ID（「订单 10002」「商品 P-1003」）、枚举别名（`aliases = "SHIPPED=已发货"`）、数量（`unit = {"单","条"}`，截断到 `max`）、相对时间（`format = DATE`）。
- **校验层**：参数键 ⊆ inputSchema，值过 JSON Schema，实体参数值 == 已识别实体，需确认工具的可信参数不许规划填写。
- **会话记忆**：`run.completed` 时写 `{domain, entities, lastTable}`；下一轮省略实体则补位（日志 `source=memory`），「第二个」按最近列表行解析。
- **澄清屏**：仍缺实体时调 `@SparkTool(clarifiesEntity = ORDER)` 的列表工具，投影为 Table，每行按钮 intent 带订单号；用户点选即下一轮。
- 种子订单：10001 / 10002 验收链路，10003 启动自检，10004 幂等 e2e，10005 / 10010 删除用例，10030 最新。

## 端点速查

| 端点 | 模块 |
|---|---|
| `POST /agent/runs`、`POST /agent/runs/{runId}/actions/{actionId}`（SSE）、`GET /agent/runs/{runId}` | web-mvc |
| `POST /internal/tool-registry/tools`、`POST /internal/tool-registry/search`、`GET /internal/tool-registry/tools/{toolId}/versions` | web-mvc（`spark.web.internal-endpoints=true`） |
| `POST /internal/tool-gateway/invoke` | 同上 |
| `GET /actuator/health` | 宿主 actuator |

契约索引见 `.harness/wiki/api-contracts.md`；契约真源在 `.harness/contracts/`。

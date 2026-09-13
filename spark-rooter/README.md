# spark-rooter — Spark Rooter 后端（Spring Boot Starter）

Java 21 / Spring Boot 3.5 / Spring AI 1.1 / Maven 多模块。**形态是一个 Starter 依赖**：任何 Java 服务引入 `com.sparkrooter:spark-rooter-spring-boot-starter`，在 `@Service` 方法上加 `@SparkTool`，即可被 `spark-ui` 用自然语言驱动。

> 一句话：Runtime 负责理解与规划（决策面），Registry 负责发现与治理（控制面），Gateway 负责安全执行（执行面），宿主的 `@SparkTool` 方法负责确定性业务执行。内核**不识别用户 / 权限 / 页面上下文**——这些全在宿主工程。

## 三步接入（见 `examples/host-demo`）

1. `pom.xml` 引入 `spark-rooter-spring-boot-starter`（本仓库先 `./mvnw -q install` 到本地仓）。
2. 任意 `@Service` 的 public 方法加 `@SparkTool(id, version, domain, name, description)`，参数 / 返回值为 record，参数组件用 `@SparkParam` 开放（未标注不进 inputSchema）、`@SparkDefault` 给默认值；有副作用的方法加 `@SparkRisk` + `@SparkPrerequisite`。启动日志 `spark-rooter: N tools registered from M beans`。
3. 接自己的身份：实现 `RunContextPropagator`（把登录态 ThreadLocal 带到 spark 工作线程）；**必须**实现 `SessionIdResolver`（会话隔离键绑登录态）：缺该 Bean 时 starter 拒绝启动并打印指引；仅本地演示可设 `spark.runtime.demo-session-resolver=true` 放行「sessionId = conversationId」的演示实现（无隔离，启动 WARN）。权限用**方法级**切面（`@Aspect` / `@PreAuthorize`），Controller 拦截器对 spark 的代理调用无效。

## 模块

| 模块 | 职责 | README |
|---|---|---|
| `spark-rooter-spi` | 注解 `@SparkTool / @SparkRisk / @SparkPrerequisite / @SparkParam / @SparkDefault`；端口 `ToolHandler`、`ScreenBuilder`、`ConfirmationRecheck`、`AuditSink`、`SessionIdResolver`、`ToolAccessPolicy`、`RunContextPropagator`、`ConversationMemory`、`SelfCheck`；预留 `ToolTransport` / `ToolProviderDiscovery`；零 Spring | — |
| `spark-rooter-contracts` | 9 个契约的 record DTO + `SchemaValidator`（契约从 `.harness/contracts/` 构建期打进 jar） | — |
| `spark-rooter-runtime` | 路由 → 抽取 → 记忆补位 → 规划 → 编排 → 令牌 → 屏 / 澄清屏 → SSE 事件 | [README](spark-rooter-runtime/README.md) |
| `spark-rooter-registry` | 注册 / 发现 / 版本；无转发端点 | [README](spark-rooter-registry/README.md) |
| `spark-rooter-gateway` | 校验 → 幂等 → 经 Spring 代理调用 → 输出校验 → 脱敏 → 审计 | [README](spark-rooter-gateway/README.md) |
| `spark-rooter-web-mvc` | `/agent/runs` SSE 端点、`/internal/**`（可选）、异常映射；唯一依赖 starter-web 的平台模块 | — |
| `spark-rooter-spring-boot-starter` | `AutoConfiguration.imports`、`spark.*` 属性、全部默认实现 `@ConditionalOnMissingBean`、`@SparkTool` 扫描 / Manifest 推导 / 代理调用适配、启动自检、readiness 指示器（宿主有 actuator 时） | — |
| `spark-rooter-redis` | **可选**。四个状态存储的 Redis 实现（`spark.storage.type=redis` 时装配），hub 多副本用；只依赖平台模块，平台模块不得反向依赖它 | — |
| `examples/demo-support` | 示例宿主的 mock 用户上下文 `DemoUserContext` + 示例领域间的 `OrderSnapshotProvider` 只读端口（纯 JDK；tenantId 等业务字段只在这里，内核不识别） | — |
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
| `spark.llm.base-url / api-key / model` | 空 | 缺任一 → `UnavailablePlanner`：启动 WARN，所有请求返回「未配置模型，无法理解请求」；兼容环境变量 `SPARK_LLM_*`（密钥禁止写进代码 / yml / 日志） |
| `spark.runtime.demo-session-resolver` | `false` | 宿主未提供 `SessionIdResolver` 时默认拒绝启动；`true` 放行演示实现（sessionId = conversationId，无会话隔离），仅本地演示 |
| `spark.llm.read-timeout` | `90s` | 上游读超时。与 `sse-timeout` 对齐——超过它没有意义（SSE 先断，用户看不到结果而后端还在等）。**推理模型需要更久时，两者要一起配大** |
| `spark.llm.circuit.enabled` | `true` | 连续传输失败后熔断，不再发请求。关掉则模型网关挂掉时每个请求各自等满 `read-timeout`，线程池很快占满 |
| `spark.llm.circuit.failure-threshold` | 2 | 连续失败阈值。**这里的「一次失败」= 3 次真实网关请求**，因为 Spring AI 的 RetryTemplate 已在内部重试 3 次；取 2 即约 6 次真实请求后熔断。只统计传输失败，模型输出不合规不计入 |
| `spark.llm.circuit.open-duration` | `30s` | 熔断静默期，之后放一个探测请求；成功则恢复，失败则重新计时 |
| `spark.runtime.run-pool / run-queue` | 8 / 32 | Run 编排池与队列。**队列满时立刻返回「当前请求较多，请稍后重试」而非静默排队**。32 ≈ 4 倍核心数：按规划耗时 9–39s 估算，排到第 32 位的等待已超出 `sse-timeout`，再排也等不到结果 |
| `spark.runtime.ping-pool` | 2 | SSE 心跳调度。队列无法设界（`ScheduledThreadPoolExecutor` 用私有的 DelayedWorkQueue），但任务是固定 15s 心跳、数量与活跃连接同阶，不会独立失控 |
| `spark.runtime.sse-timeout / token-ttl / memory-ttl` | 90s / 10m / 30m | SSE 超时、确认令牌有效期、会话记忆有效期 |
| `spark.gateway.tool-pool / tool-queue` | 8 / 64 | 工具执行池与队列。64 取 `run-queue` 的 2 倍，因为一个 Run 可能有多个工具步骤（退款链是 3 步）。队列满时该 Run 失败为 `TOOL_EXECUTION_FAILED` |
| `spark.gateway.max-concurrent-per-session` | 4 | 单会话在飞工具调用上限，≤ 0 关闭。防只读查询洪水：一个会话不能占满整池 |
| `spark.gateway.idempotency-ttl` | `24h` | 幂等结果保留窗口。内存实现按它淘汰已完成记录（每 64 次 complete 扫一遍）；Redis 实现作 key TTL。应长于任何合理的客户端重试窗口 |
| `spark.storage.type` | `memory` | 四个状态存储（Run / 确认令牌 / 幂等 / 会话记忆）放哪。`memory` 单副本可用、重启即丢；`redis` 需引 `spark-rooter-redis` 并配 `spring.data.redis.*`，hub 可任意多副本。配了 redis 却没装配到会启动 WARN 并回落内存——多副本下那是数据错误，不是降级 |
| `spark.storage.redis.claim-ttl` | `5m` | 幂等占位 key 的存活时间，**必须大于任何工具的 `timeoutMs`**：占位在 owner 执行完之前过期，等待方会重 claim，同一个写操作就执行两次 |
| `spark.web.base-path` | `/agent` | `/runs` 端点前缀 |
| `spark.web.internal-endpoints` | `false` | 是否装配 `/internal/**` 三个端点 |
| `spark.selfcheck.enabled` | `false` | 启动自检（示例宿主打开；生产建议关） |
| `spark.owner-team` | `host` | 推导 Manifest 的 `owner.team` |

`SPARK_LLM_BASE_URL` 以 `/v{n}` 结尾时只追加 `/chat/completions`，否则按 Spring AI 默认追加 `/v1/chat/completions`。

### LLM 埋点

每次规划调用在 `LLM_METRICS` logger 落一行，含 outcome（`planned` / `clarify` / `no_capability` / `invalid_output` / `transport_error` / `circuit_open`）、耗时、token 用量、尝试次数：

```
llm outcome=planned durationMs=8421 promptTokens=1832 completionTokens=96 attempts=1 circuitOpen=false
llm outcome=circuit_open durationMs=0 promptTokens=- completionTokens=- attempts=0 circuitOpen=true
```

token 为 `-` 表示上游网关未返回 usage（不是 0 —— 那会被误读成「没消耗」）。要接 Micrometer / Prometheus，宿主定义 `LlmMetricsSink` Bean 即覆盖默认的日志实现。

## 参数与多轮

- **声明层**：只有 `@SparkParam` 组件进 inputSchema；`@SparkDefault` 写进 schema `default`。
- **规划层**（模型）：注解里的 description / verbs / `aliases` / `unit` / `label` / `entity` 逐条随候选进 prompt，模型据此选工具、填参数（一律字符串），「第二个」直接从最近列表行取 ID。
- **校验层**（`PlanValidator`）：参数键 ⊆ inputSchema，值过 JSON Schema，实体参数值必须原样出自原话或会话上下文并匹配 `pattern`，需确认工具前置齐全且可信参数不许模型填写；不合规喂回模型重试一次。
- **会话记忆**：`run.completed` 时写 `{domain, entities, lastTable}`；下一轮作为「会话上下文」交给模型，「第二个」按最近列表行解析。
- **澄清屏**：模型判定缺实体（或校验复核实体值不在上下文）时调 `@SparkTool(clarifiesEntity = "order")` 的列表工具，投影为 Table，每行按钮 intent 带 ID；用户点选即下一轮。
- 种子订单：10001 / 10002 验收链路，10003 启动自检，10004 幂等 e2e，10005 / 10010 删除用例，10030 最新。

## 端点速查

| 端点 | 模块 |
|---|---|
| `POST /agent/runs`、`POST /agent/runs/{runId}/actions/{actionId}`（SSE）、`GET /agent/runs/{runId}` | web-mvc |
| `POST /internal/tool-registry/tools`、`POST /internal/tool-registry/search`、`GET /internal/tool-registry/tools/{toolId}/versions` | web-mvc（`spark.web.internal-endpoints=true`） |
| `POST /internal/tool-gateway/invoke` | 同上 |
| `GET /actuator/health` | 宿主 actuator |

契约索引见 `.harness/wiki/api-contracts.md`；契约真源在 `.harness/contracts/`。

# Rule: 后端编码规范（Backend Standard，Java / Spring Boot）

> 每条规则对应一个真实踩过的坑。能被 `mvn verify` 机器校验的优先写进构建，不能的才写文字。

## 1. 工具链

- **Java 21**（LTS），`<release>21</release>`；禁止使用 preview 特性。
- **Spring Boot 3.5.x**，Maven 多模块，仓库内提供 `mvnw`；所有命令用 `./mvnw`，不依赖全局 mvn 版本。
- **LLM 接入只用 Spring AI 1.1.x**（`spring-ai-starter-model-openai`，`base-url` 指向 OpenAI 兼容服务）。**禁止**引入 Spring AI Alibaba、LangChain4j 或其他模型 SDK；模型只能"提议"工具调用，`ChatClient` 必须 `internalToolExecutionEnabled(false)`，执行一律经 Tool Gateway。Spring AI 与 Spring Boot 版本通过 BOM 对齐。
- 编译开启 `-Xlint:all -Werror`；`spotless:check`（google-java-format）作为格式门禁。
- 后端质量门禁 = `./mvnw -q -B verify`（含 JUnit 5 单元测试，surefire 执行，任一失败退出码非 0），退出码 0。测试与 `src/main` 同受 `-Xlint:all -Werror` 与 spotless 约束；测试类一律 `final`。

## 2. 类型与数据

- **金额、ID 一律 `String`**。金额运算用 `BigDecimal`，序列化回 `String`；**禁止** `long` / `double` 表示金额或 ID。
- 时间用 `java.time.Instant` / `OffsetDateTime`，序列化为 ISO-8601 字符串。
- DTO 用 `record`；字段用 `@NotNull` 等 Jakarta Validation 注解，Controller 入参加 `@Valid`。
- 枚举可用，但对外序列化为字符串；未知值反序列化必须失败而非默认。
- **禁止** `Object` / `Map<String,Object>` 作为对外 DTO 字段类型，除非该字段在契约中就是自由 JSON（如 `arguments`），此时用 `JsonNode`。

## 3. 契约与校验

- 所有对外 HTTP / SSE 结构必须在 `.harness/contracts/` 有 Schema；后端用 JSON Schema 校验器（networknt）在边界校验：
  - 入站请求体：校验失败返回 `400` + `ErrorResponse`。
  - 工具调用参数：Gateway 按工具 Manifest 的 `inputSchema` 校验。
  - 工具返回值：Gateway 按 `outputSchema` 校验，失败视为工具错误。
  - LLM 输出：按预期 Schema 校验，失败重试一次后转人工。
- **禁止**在业务代码里手写 JSON 字符串拼接。

## 4. 分层与依赖

- 包结构 `api / application / domain / infra`。`domain` 不依赖 Spring、不依赖 Jackson。
- Controller 只做：参数绑定、校验、调用 application、映射响应。**禁止**在 Controller 写业务逻辑。
- 跨模块只依赖对方的 `api` 包公开接口（`ToolSearchPort` / `ToolInvokePort`）；**禁止**依赖对方 `infra`、`domain`、`application`。`check-module-deps` 机械校验。
- Agent Runtime 只能通过 `ToolGatewayClient` 接口执行工具，通过 `ToolRegistryClient` 接口发现工具。

## 5. 错误处理

- 统一 `ErrorResponse { code, message, traceId }`，由 `@RestControllerAdvice` 输出。
- 业务错误抛领域异常（继承 `DomainException`），基础设施错误抛 `InfrastructureException`；**禁止** `catch (Exception e) {}` 吞掉。
- 日志用 SLF4J，禁止 `System.out`；每条日志带 `runId` / `toolCallId` MDC。
- 不在日志中输出用户输入原文、Token、密钥。

## 6. 并发与幂等

- 有副作用的工具调用必须携带 `idempotencyKey`，Gateway 用 `(tenantId, idempotencyKey)` 去重。
- 超时、重试、熔断由 Gateway 依据 Manifest `execution` 配置执行；**禁止**在 Runtime 或领域服务里私自重试。
- Run 状态机迁移必须是幂等的，同一事件重放不产生第二次副作用。
- **线程池必须有界**：禁止 `Executors.newFixedThreadPool` / `newCachedThreadPool` 等工厂方法（队列无界，过载时任务堆到 OOM，用户侧表现为连接挂着既不失败也不返回）。统一用 `NamedThreads.boundedPool(core, queueCapacity, prefix)`——显式 `ThreadPoolExecutor` + `ArrayBlockingQueue` + `AbortPolicy` + 命名守护线程。
- **拒绝必须对用户可见**：`RejectedExecutionException` 不能只落日志。SSE 场景下 emitter 已返回给客户端，无法改 HTTP 状态码，必须发 `run.failed` 终态事件并 close，否则连接会挂到 SSE 超时。
- **例外**：`ScheduledThreadPoolExecutor` 的队列固定为私有 `DelayedWorkQueue`，四个构造器都不接受 `BlockingQueue`，设不了界。此类池允许无界，但**必须在注释里说明任务量为何不会失控**（如「固定周期心跳，数量与活跃连接同阶」）。
- **熔断只统计传输失败**：业务层面的失败（如模型输出不合规）不得计入熔断计数，否则「能用但不够好」会被判成「服务宕机」。

## 7. 配置与安全

- 密钥、LLM 配置只从环境变量读取（`SPARK_LLM_BASE_URL`、`SPARK_LLM_API_KEY`、`SPARK_LLM_MODEL`）；**禁止**写入代码或 `application.yml`。三者任一缺失时 `LlmClient` 为 `UnavailablePlanner`：启动 WARN，所有请求直接失败并返回「未配置模型，无法理解请求」，不做规则兜底。内核不做领域路由与规则规划，模型在全部可发现候选里选，`PlanValidator` 做通用校验。
- Registry 对外返回的工具元数据**不含**内部地址、凭据、Owner 联系方式以外的敏感信息。
- 对外端点**不带身份头**：内核不识别用户；宿主用自己的拦截器 / 登录态建立上下文，并实现 `SessionIdResolver`（会话隔离键）与 `RunContextPropagator`（跨到 spark 工作线程）。
- **测试替身（fake planner）只允许放在宿主工程并以 profile 隔离**，内核不得出现：它必须认识领域动词，而平台模块有 `DOMAIN_WORDS` 红线。替身只负责「原话 → `PlanDraft`」这一段（真模型的职责），产出的草案必须经 `PlanValidator.decide`，与真模型走同一条校验路径——不得自行构造 `Plan` 绕过校验。生产 profile 下替身不得装配。**缺 `SessionIdResolver` Bean 时 starter 拒绝启动**；只有本地演示才设 `spark.runtime.demo-session-resolver=true` 放行「sessionId = conversationId」的演示实现（无会话隔离，启动 WARN）。

## 7b. 多副本（feat-production-hardening-20260912）

hub 能不能起多个副本，取决于状态放哪。规则：

- **哪些状态必须共享**：Run、确认令牌、幂等记录、会话记忆。四者任一留在进程内，两副本就会各判各的：写操作在 A claim、重试落 B → 重复执行；令牌在 A 签发、确认到 B → 找不到。
- **哪些可以留在进程内**：工具注册表（单体形态每副本各自扫描同一批 `@SparkTool`；provider 形态 Manifest 推给每个副本）、`ToolMetaRegistry`、线程池、限流计数（`SessionConcurrencyLimiter` 按副本限流是可接受的近似）、熔断器状态（按副本熔断反而更稳）。
- **编排器不得持有按 runId 的进程内缓存**。确认所需的一切进 `Run` 聚合。判据：第二个 `RunOrchestrator` 实例只共享 `RunRepository` 就能完成确认——`RunOrchestratorConfirmTest.confirmOnAnotherReplicaSucceedsWithSharedRepository` 锁住这条。
- **内存实现必须有界**：`InMemoryConfirmationTokenStore` 写入时清过期项；`InMemoryIdempotencyStore` 已完成记录按 `spark.gateway.idempotency-ttl` 淘汰（占位中的不动）。「加了存储又引入新泄漏」是典型错误，每个内存 Map 都要回答"什么时候变小"。
- **配了 `spark.storage.type=redis` 却装配不到，WARN 而不是静默回落**：多副本下回落内存是数据错误不是降级。不拒绝启动是因为单副本 + 误配的宿主不该被拒。
- **Redis 实现的两条原子性**：令牌 `GETDEL`；幂等 `SET NX PX`。`claim-ttl` 必须大于任何工具的 `timeoutMs`，否则占位在 owner 执行完之前过期、等待方重 claim → 双执行。
- **用户原话不进共享存储**：`RunSnapshot` 刻意不带 `Run.message`，与日志红线同一口径。

`e2e-multi-instance.sh` 用两个 hub 共享一个 Redis 验证这条：在 A 发起、在 B 确认、回 A 重放被拒、跨副本幂等 replayed、A 写记忆 B 读到。

## 7c. 客户端断开

SSE 客户端断开后，编排器在**只读步骤**前检查 `RunEventSink.isClosed()`，已断则终止 Run（`INTERNAL_ERROR`，日志 `run abandoned … reason=client_gone`）——没人看的结果不值得再调一次工具。**写步骤照跑**（不留半截写链），确认后的执行路径不检查（用户已明确确认）。`sideEffect` 从 `ToolMetaRegistry` 取，取不到视为写（fail-safe）——http provider 工具目前没有 `ToolMeta`，这条优化对它们不生效，已记入已知限制。

## 7d. 健康探针

starter 在宿主有 actuator 时注册 `sparkRooter` 健康指示器：规划器为 `UnavailablePlanner` → DOWN；否则 UP。**熔断 OPEN 不改状态只进 detail**——`LlmCircuitBreaker` 的 OPEN → HALF_OPEN 转换只发生在真实请求调用 `shouldSkip()` 时，熔断即摘流量会让它永不恢复。宿主把 `sparkRooter` 加进 readiness 组；重启类探针（liveness / Docker HEALTHCHECK）**不要**打根 `/actuator/health`——它聚合了 sparkRooter，没配模型时是 DOWN，那是「不接流量」不是「进程坏了」。

## 8. 提交

- Conventional Commits，scope 用模块名：`feat(gateway): validate output schema`。
- footer 含 `Change: {change-id}`。

## Provider 侧约束（微服务形态）

feat-provider-http-transport-20260912 起，领域服务可作为独立进程（provider）接入。provider 侧额外遵守：

- **JDK 下限 17**。provider 装在别人的业务服务里，企业存量大量停在 17。它依赖的 `spi` / `contracts` 同样是 17（共享契约层取两边下限），否则 17 的进程加载 21 字节码会 `UnsupportedClassVersionError`。
- **薄依赖**：pom 只许 `spi` + `contracts` + `spring-boot-autoconfigure` + `spring-web` + `spring-aop`。禁 `spring-ai-*`（provider 不做规划）、禁 hub 模块（runtime / registry / gateway / web-mvc）、禁 `spring-boot-starter-web`（不绑宿主 web 栈选型，运行期容器由宿主已有的 starter 提供）。`check-module-deps` 机械守护。
- **不得自行重试**。`ToolHandler` 的既有约定在跨进程后后果被放大：provider 自己重试 × hub 重试 = 指数放大。重试策略按 Manifest 由 hub 的 Gateway 统一决定。
- **认证 fail-fast**：`spark.provider.token` / `service-name` / `hub-url` 缺任一项拒绝启动。安全相关的缺省不能是宽松的（与 `SessionIdResolver` 同一决策）。
- **返回前脱敏**：与 hub 同一 `SENSITIVE_KEYS` 口径。脱敏若只在 hub 侧做，原文已过网络、已进 provider 日志。
- **身份归宿主**：provider 源码同样禁 `userId` / `tenantId` / `Principal`。`RunContextPropagator` 在 http 形态下不生效，要拿身份靠 hub 传来的 `sessionId` 或自己的网关鉴权。
- **无 web 栈时降级要显式**：执行端点用 `@ConditionalOnClass` 守护，不装配时**必须 WARN**——工具注册成功却永远调不通是极难排查的故障。

## 指标与埋点

feat-runtime-limits-and-metrics-20260912 起，平台出三类指标（LLM / 工具 / Run），经 spi 端口导出。

### 标签必须低基数（红线）

**禁止**把用户标识、会话标识、业务实体标识、运行标识作为指标标签（`sessionId` / `runId` / `conversationId` / `toolCallId` / 各类业务 ID）。它们基数无上界，会打爆时序库——**"看起来有监控但把监控系统打挂"比没有监控更糟**。

允许的标签只有有限集合：`toolId`（工具数量有限）、`outcome` / `status` / `code`（枚举）、`kind`（prompt/completion）。

实现里**标签集合硬编码**，不接受运行期传入的动态标签（`MicrometerMetricsSinks`）。要追溯单次调用去查审计（`AuditSink` 逐次留痕，含 `argsDigest`），不要靠指标。

### 埋点不得拖垮业务

`ToolMetricsSink` / `RunMetricsSink` / `LlmMetricsSink` 的实现**不应抛异常**。调用方（Gateway / Runtime）会捕获并记 WARN，不影响执行结果——监控故障让正常请求失败是经典事故。

同理 `AuditSink`：它被调用的位置在「工具已执行完」与「返回结果」之间，抛异常会让副作用已发生却对调用方表现为失败，用户重试造成**重复副作用**。Gateway 捕获并记 ERROR（字段齐全，可据日志补账）。

取舍是明确的：审计/指标丢失可告警补账，重复扣款不可逆。

### Micrometer 是可选依赖

只有 hub starter 可依赖 Micrometer，且**必须 `<optional>true</optional>`**——不带 optional 会传递给所有宿主，包括 provider 与用别的监控栈的宿主。`check-module-deps` 机械守护（含 optional 标记检查），双向自证。

**`@ConditionalOnBean` 在 `@Import` 进来的配置类上不可靠**：求值早于 actuator 注册 `MeterRegistry`，条件永不成立且**静默退回**默认实现。用 `ObjectProvider` 在注入时解析。这条是实测踩出来的——单测全绿、启动正常、指标一个都没有。

## 内核自保阈值

以下阈值是**常量而非配置项**，与 `LlmPlanner.MAX_ATTEMPTS` / `PromptBuilder.MAX_TEXT` / `ClarificationScreen.MAX_COLUMNS` 同例——宿主无需调，真需要时再引入配置项（届时才有实际依据）：

| 阈值 | 值 | 防什么 |
|---|---|---|
| `PlanValidator.MAX_PLAN_STEPS` | 6 | 模型规划出几十步，每步都是真实工具调用。取实测最长链 3 步的 2 倍；刻意不取 8（与 `FakeLlmPlanner` 的 8 处 `DraftStep` 重合会在加测试场景时撞线） |

可配的运行期限制（宿主需按容量调整）：

| 配置项 | 默认 | 说明 |
|---|---|---|
| `spark.gateway.max-concurrent-per-session` | 4 | 单会话在飞工具调用上限，≤ 0 关闭。防只读查询洪水——写操作已被幂等 claim 序列化。与 `toolQueue=64` 挂钩：需 16 个并发会话才占满池。**改 toolQueue 时同步复核** |
